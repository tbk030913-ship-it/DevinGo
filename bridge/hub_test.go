// Hub tests drive the bridge end-to-end over real WebSockets against a
// scripted fake `devin acp` (same pattern as the sleeper in main_test.go):
// an NDJSON responder that logs every inbound line to $DEVINGO_TEST_STATS.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
)

type rpcMsg = map[string]any

// --- fake devin -------------------------------------------------------------
//
// Behavior table:
//
//	initialize   -> canned result (marker: agentInfo.name == "stubdevin",
//	                authMethods: [] so the gateway skips authenticate)
//	session/new  -> sessionId S001, S002, ... (counter)
//	session/load -> two replay updates for params.sessionId, then result
//	session/prompt -> three chunk updates; "PERM" in the prompt text also
//	                emits session/request_permission with devin-side id 910,
//	                then the prompt result
//	session/set_config_option -> updates the stub's current mode/model and
//	                answers with the full configOptions array (as devin does)
//	session/list -> one canned entry (sessionId S001)
//	test/hang    -> never responds (in-flight request for proc-death test)
//	test/ghost   -> one session/update for unhosted GHOST-SESSION, then {}
//	anything else with an id -> {}
//	response frames / notifications -> only logged
const testDevinSrc = `package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"strings"
)

type rpc = map[string]any

var seq int
var curMode = "accept-edits"
var curModel = "swe-2-high"

var modeChoices = []any{
	rpc{"id": "accept-edits", "name": "Accept Edits"},
	rpc{"id": "plan", "name": "Plan"},
	rpc{"id": "ask", "name": "Ask"},
}
var modelChoices = []any{
	rpc{"id": "swe-2-high", "name": "SWE-2 High"},
	rpc{"id": "adaptive", "name": "Adaptive"},
}

func send(m rpc) {
	b, _ := json.Marshal(m)
	fmt.Println(string(b))
}

// Both config shapes: devin answers configOptions, the classic ACP
// modes/models objects ride along so attach-snapshot sync has both
// surfaces to keep current (the client prefers modes/models).
func sessionConfig() rpc {
	return rpc{
		"configOptions": []any{
			rpc{"id": "mode", "category": "mode", "type": "select", "currentValue": curMode,
				"options": modeChoices},
			rpc{"id": "model", "category": "model", "type": "select", "currentValue": curModel,
				"options": modelChoices},
		},
		"modes":  rpc{"currentModeId": curMode, "availableModes": modeChoices},
		"models": rpc{"currentModelId": curModel, "availableModels": modelChoices},
	}
}

func respond(id any, result rpc) {
	send(rpc{"jsonrpc": "2.0", "id": id, "result": result})
}

func notifyUpdate(sid, text string) {
	send(rpc{"jsonrpc": "2.0", "method": "session/update", "params": rpc{
		"sessionId": sid,
		"update":    rpc{"sessionUpdate": "agent_message_chunk", "content": rpc{"type": "text", "text": text}},
	}})
}

func main() {
	stats := os.Getenv("DEVINGO_TEST_STATS")
	// DEVINGO_DIE_ONCE names a flag file: the first boot creates it and
	// exits before answering anything, so the respawn-retry test gets a
	// failed handshake; the file then gates later boots healthy.
	if gate := os.Getenv("DEVINGO_DIE_ONCE"); gate != "" {
		if _, err := os.Stat(gate); os.IsNotExist(err) {
			os.WriteFile(gate, []byte("x"), 0o644)
			os.Exit(1)
		}
	}
	if stats != "" {
		// A boot record per spawn: argv + the refusal-fallback env — the
		// spawn-profile test (W8.3) asserts a profiled proc was launched
		// with exactly these.
		if f, err := os.OpenFile(stats, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644); err == nil {
			f.WriteString("BOOT " + strings.Join(os.Args[1:], " ") +
				" ENV_FB=" + os.Getenv("DEVIN_REFUSAL_FALLBACK") + "\n")
			f.Close()
		}
	}
	sc := bufio.NewScanner(os.Stdin)
	sc.Buffer(make([]byte, 64<<10), 16<<20)
	for sc.Scan() {
		line := sc.Text()
		if stats != "" {
			if f, err := os.OpenFile(stats, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644); err == nil {
				f.WriteString(line + "\n")
				f.Close()
			}
		}
		var m rpc
		if json.Unmarshal([]byte(line), &m) != nil {
			continue
		}
		method, _ := m["method"].(string)
		id, hasID := m["id"]
		if method == "" || !hasID {
			continue // response to our own request, or a notification
		}
		params, _ := m["params"].(rpc)
		sid, _ := params["sessionId"].(string)
		switch method {
		case "initialize":
			respond(id, rpc{
				"protocolVersion":   1,
				"agentInfo":         rpc{"name": "stubdevin", "version": "0.0.1"},
				"agentCapabilities": rpc{"loadSession": true},
				"authMethods":       []any{},
			})
		case "authenticate":
			respond(id, rpc{})
		case "session/new":
			seq++
			res := sessionConfig()
			res["sessionId"] = fmt.Sprintf("S%03d", seq)
			respond(id, res)
		case "session/load":
			notifyUpdate(sid, "replay-1")
			notifyUpdate(sid, "replay-2")
			respond(id, sessionConfig())
		case "session/set_config_option":
			cid, _ := params["configId"].(string)
			val, _ := params["value"].(string)
			switch cid {
			case "mode":
				curMode = val
			case "model":
				curModel = val
			}
			respond(id, sessionConfig())
		case "test/modeupdate":
			// Emits a current_mode_update ahead of its empty result —
			// exercises the notification-side snapshot sync.
			send(rpc{"jsonrpc": "2.0", "method": "session/update", "params": rpc{
				"sessionId": sid,
				"update":    rpc{"sessionUpdate": "current_mode_update", "currentModeId": "ask"},
			}})
			respond(id, rpc{})
		case "session/prompt":
			text := ""
			if blocks, ok := params["prompt"].([]any); ok && len(blocks) > 0 {
				if b0, ok := blocks[0].(rpc); ok {
					text, _ = b0["text"].(string)
				}
			}
			notifyUpdate(sid, "chunk-1")
			notifyUpdate(sid, "chunk-2")
			notifyUpdate(sid, "chunk-3")
			if strings.Contains(text, "PERM") {
				send(rpc{"jsonrpc": "2.0", "id": 910, "method": "session/request_permission", "params": rpc{
					"sessionId": sid,
					"toolCall":  rpc{"toolCallId": "tc1", "title": "run tests"},
					"options":   []any{rpc{"optionId": "o1", "name": "Allow"}, rpc{"optionId": "o2", "name": "Deny"}},
				}})
			}
			respond(id, rpc{"stopReason": "end_turn"})
		case "session/list":
			if params["content_search"] == "HANG" {
				// Never responds — keeps _devingo/sessions in-flight so
				// the listNext supersede path can be exercised.
				break
			}
			respond(id, rpc{"sessions": []any{
				rpc{"sessionId": "S001", "cwd": "C:/proj", "title": "canned-one", "updatedAt": "2026-01-02T03:04:05Z"},
			}})
		case "test/hang":
			// Never responds: leaves the client request in-flight so a
			// process death must error it (proc-death test).
		case "test/ghost":
			// Emits traffic for a session the hub never hosted — exercises
			// the lazily-created-session path in devinNotification.
			notifyUpdate("GHOST-SESSION", "boo")
			respond(id, rpc{})
		default:
			respond(id, rpc{})
		}
	}
}
`

var stubOnce sync.Once
var stubBin string

// fakeDevin compiles the stub once for the whole test run (temp dir is
// intentionally not test-scoped — later tests reuse the binary).
func fakeDevin(t *testing.T) string {
	t.Helper()
	stubOnce.Do(func() {
		dir, err := os.MkdirTemp("", "devingo-testdevin")
		if err != nil {
			t.Fatalf("mktemp: %v", err)
		}
		bin := filepath.Join(dir, "testdevin")
		if runtime.GOOS == "windows" {
			bin += ".exe"
		}
		src := filepath.Join(dir, "main.go")
		if err := os.WriteFile(src, []byte(testDevinSrc), 0o644); err != nil {
			t.Fatalf("write stub: %v", err)
		}
		if out, err := exec.Command("go", "build", "-o", bin, src).CombinedOutput(); err != nil {
			t.Fatalf("build stub: %v\n%s", err, out)
		}
		stubBin = bin
	})
	return stubBin
}

// setupHub swaps in a fresh hub pointed at bin, wires the stub's stats file,
// and arranges for the resident process to be killed at test end.
func setupHub(t *testing.T, bin string) string {
	t.Helper()
	stats := filepath.Join(t.TempDir(), "stats.ndjson")
	t.Setenv("DEVINGO_TEST_STATS", stats)
	oldBin, oldDir, oldToken, oldHub := *devinBin, *defaultDir, *token, bridgeHub
	*devinBin, *token, bridgeHub = bin, "", newHub()
	*defaultDir = t.TempDir()
	t.Cleanup(func() {
		bridgeHub.mu.Lock()
		procs := make([]*devinProc, 0, len(bridgeHub.procs))
		for _, p := range bridgeHub.procs {
			procs = append(procs, p)
		}
		bridgeHub.mu.Unlock()
		for _, p := range procs {
			killProcessTree(p.cmd)
		}
		*devinBin, *defaultDir, *token, bridgeHub = oldBin, oldDir, oldToken, oldHub
	})
	return stats
}

func newTestServer(t *testing.T) *httptest.Server {
	mux := http.NewServeMux()
	mux.HandleFunc("/acp", acpHandler)
	mux.HandleFunc("/fs/write", fsWriteHandler)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

// --- ws test client ---------------------------------------------------------

type testClient struct {
	conn  *websocket.Conn
	inbox chan rpcMsg
	held  []rpcMsg
}

func dialClient(t *testing.T, srv *httptest.Server) *testClient {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(ctx,
		"ws"+strings.TrimPrefix(srv.URL, "http")+"/acp", nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	tc := &testClient{conn: conn, inbox: make(chan rpcMsg, 4096)}
	go func() {
		defer close(tc.inbox)
		for {
			_, data, err := conn.Read(context.Background())
			if err != nil {
				return
			}
			var m rpcMsg
			if json.Unmarshal(data, &m) == nil {
				tc.inbox <- m
			}
		}
	}()
	t.Cleanup(func() { conn.CloseNow() })
	return tc
}

func (tc *testClient) send(t *testing.T, m rpcMsg) {
	t.Helper()
	b, _ := json.Marshal(m)
	if err := tc.conn.Write(context.Background(), websocket.MessageText, b); err != nil {
		t.Fatalf("ws write: %v", err)
	}
}

func (tc *testClient) request(t *testing.T, id any, method string, params any) {
	t.Helper()
	m := rpcMsg{"jsonrpc": "2.0", "id": id, "method": method}
	if params != nil {
		m["params"] = params
	}
	tc.send(t, m)
}

// next pops the next inbound frame (buffered skips first).
func (tc *testClient) next(t *testing.T, d time.Duration) rpcMsg {
	t.Helper()
	if len(tc.held) > 0 {
		m := tc.held[0]
		tc.held = tc.held[1:]
		return m
	}
	select {
	case m, ok := <-tc.inbox:
		if !ok {
			t.Fatalf("conn closed while waiting for frame")
		}
		return m
	case <-time.After(d):
		t.Fatalf("timeout waiting for frame")
		return nil
	}
}

// takeHeld removes and returns the first held frame matching pred.
func (tc *testClient) takeHeld(pred func(rpcMsg) bool) (rpcMsg, bool) {
	for i, m := range tc.held {
		if pred(m) {
			tc.held = append(tc.held[:i], tc.held[i+1:]...)
			return m, true
		}
	}
	return nil, false
}

func isResponseFor(id any) func(rpcMsg) bool {
	return func(m rpcMsg) bool {
		return m["method"] == nil && fmt.Sprint(m["id"]) == fmt.Sprint(id)
	}
}

// awaitID reads until the response carrying id arrives; unrelated frames are
// held so later next() calls still see them in order.
func (tc *testClient) awaitID(t *testing.T, id any, d time.Duration) rpcMsg {
	t.Helper()
	deadline := time.Now().Add(d)
	for {
		if m, ok := tc.takeHeld(isResponseFor(id)); ok {
			return m
		}
		remaining := time.Until(deadline)
		if remaining <= 0 {
			t.Fatalf("timeout waiting for response id=%v", id)
		}
		select {
		case m, ok := <-tc.inbox:
			if !ok {
				t.Fatalf("conn closed while waiting for id=%v", id)
			}
			tc.held = append(tc.held, m)
		case <-time.After(remaining):
			t.Fatalf("timeout waiting for response id=%v", id)
		}
	}
}

// awaitMethod reads until a frame with the given method arrives.
func (tc *testClient) awaitMethod(t *testing.T, method string, d time.Duration) rpcMsg {
	t.Helper()
	deadline := time.Now().Add(d)
	for {
		if m, ok := tc.takeHeld(func(m rpcMsg) bool { return m["method"] == method }); ok {
			return m
		}
		remaining := time.Until(deadline)
		if remaining <= 0 {
			t.Fatalf("timeout waiting for %s", method)
		}
		select {
		case m, ok := <-tc.inbox:
			if !ok {
				t.Fatalf("conn closed while waiting for %s", method)
			}
			tc.held = append(tc.held, m)
		case <-time.After(remaining):
			t.Fatalf("timeout waiting for %s", method)
		}
	}
}

// nextWithin is next()'s negative counterpart: the next frame, or false
// when none arrives in time — used for silence assertions.
func (tc *testClient) nextWithin(d time.Duration) (rpcMsg, bool) {
	if len(tc.held) > 0 {
		m := tc.held[0]
		tc.held = tc.held[1:]
		return m, true
	}
	select {
	case m, ok := <-tc.inbox:
		if !ok {
			return nil, false
		}
		return m, true
	case <-time.After(d):
		return nil, false
	}
}

// drain consumes whatever is already on the wire (held + arriving) until
// d elapses — clears attach replays before a silence assertion.
func (tc *testClient) drain(d time.Duration) {
	deadline := time.Now().Add(d)
	for {
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return
		}
		if len(tc.held) > 0 {
			tc.held = tc.held[1:]
			continue
		}
		select {
		case _, ok := <-tc.inbox:
			if !ok {
				return
			}
		case <-time.After(remaining):
			return
		}
	}
}

func initAndNew(t *testing.T, tc *testClient) string {
	t.Helper()
	tc.request(t, 1, "initialize", rpcMsg{"protocolVersion": 1, "clientCapabilities": rpcMsg{}})
	resp := tc.awaitID(t, 1, 20*time.Second)
	if resp["error"] != nil {
		t.Fatalf("initialize: %v", resp["error"])
	}
	tc.request(t, 2, "session/new", rpcMsg{"cwd": "C:/proj", "mcpServers": []any{}})
	resp = tc.awaitID(t, 2, 20*time.Second)
	if resp["error"] != nil {
		t.Fatalf("session/new: %v", resp["error"])
	}
	res, _ := resp["result"].(rpcMsg)
	sid, _ := res["sessionId"].(string)
	if sid == "" {
		t.Fatalf("session/new result missing sessionId: %v", resp)
	}
	return sid
}

func readStats(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return string(b)
}

func countStats(t *testing.T, path, substr string) int {
	return strings.Count(readStats(t, path), substr)
}

// --- the tests ---------------------------------------------------------------

// (a) client initialize is answered from the gateway's cached result — devin
// sees exactly one initialize (the gateway's own) across multiple clients.
func TestInitializeServedFromCache(t *testing.T) {
	stats := setupHub(t, fakeDevin(t))
	srv := newTestServer(t)

	c1 := dialClient(t, srv)
	c1.request(t, 1, "initialize", rpcMsg{"protocolVersion": 1})
	r1 := c1.awaitID(t, 1, 20*time.Second)
	res, _ := r1["result"].(rpcMsg)
	info, _ := res["agentInfo"].(rpcMsg)
	if info["name"] != "stubdevin" {
		t.Fatalf("initialize result not the cached stub result: %v", res)
	}

	c2 := dialClient(t, srv)
	c2.request(t, 7, "initialize", rpcMsg{"protocolVersion": 1})
	r2 := c2.awaitID(t, 7, 20*time.Second)
	res2, _ := r2["result"].(rpcMsg)
	info2, _ := res2["agentInfo"].(rpcMsg)
	if info2["name"] != "stubdevin" {
		t.Fatalf("second initialize not served from cache: %v", res2)
	}

	if n := countStats(t, stats, `"method":"initialize"`); n != 1 {
		t.Fatalf("devin saw %d initialize calls, want 1 (gateway's own)", n)
	}
}

// (a2) the gateway's own initialize declares the full client-capability
// set — devin keys its agentCapabilities._meta echo off the flags inside
// clientCapabilities._meta (probe: acptest/probe-logs/01-init-all.log).
func TestGatewayInitParamsDeclaresExtensions(t *testing.T) {
	params := gatewayInitParams()
	if params["protocolVersion"] != 1 {
		t.Fatalf("protocolVersion = %v, want 1", params["protocolVersion"])
	}
	caps, _ := params["clientCapabilities"].(map[string]any)
	if caps == nil {
		t.Fatal("clientCapabilities missing")
	}
	fs, _ := caps["fs"].(map[string]any)
	if fs["readTextFile"] != false || fs["writeTextFile"] != false {
		t.Fatalf("fs capability = %v, want read/write false", fs)
	}
	if caps["terminal"] != false {
		t.Fatalf("terminal = %v, want false", caps["terminal"])
	}
	if _, ok := caps["elicitation"].(map[string]any); !ok {
		t.Fatalf("elicitation = %v, want empty object", caps["elicitation"])
	}
	meta, _ := caps["_meta"].(map[string]any)
	for _, k := range []string{
		"cognition.ai/revert",
		"cognition.ai/multiRootWorkspace",
		"cognition.ai/subagentSupport",
		"cognition.ai/sessionRename",
		"cognition.ai/sessionShare",
		"cognition.ai/sessionListContentSearch",
	} {
		if meta[k] != true {
			t.Fatalf("clientCapabilities._meta[%q] = %v, want true", k, meta[k])
		}
	}
	top, _ := params["_meta"].(map[string]any)
	if top["cognition.ai/revert"] != true {
		t.Fatalf("top-level _meta[cognition.ai/revert] = %v, want true",
			top["cognition.ai/revert"])
	}
	info, _ := params["clientInfo"].(map[string]any)
	if info["name"] != "devingo-bridge" {
		t.Fatalf("clientInfo.name = %v, want devingo-bridge", info["name"])
	}
}

// (b) client request ids round-trip untouched even though devin saw internal
// numeric ids.
func TestRequestIDRewriting(t *testing.T) {
	stats := setupHub(t, fakeDevin(t))
	srv := newTestServer(t)
	c := dialClient(t, srv)

	c.request(t, "abc", "initialize", rpcMsg{"protocolVersion": 1})
	if resp := c.awaitID(t, "abc", 20*time.Second); resp["id"] != "abc" {
		t.Fatalf("initialize response id = %v, want \"abc\"", resp["id"])
	}
	c.request(t, 99, "session/list", rpcMsg{})
	if resp := c.awaitID(t, 99, 20*time.Second); fmt.Sprint(resp["id"]) != "99" {
		t.Fatalf("session/list response id = %v, want 99", resp["id"])
	}
	c.request(t, "zz-1", "session/list", rpcMsg{"cwd": "C:/proj"})
	if resp := c.awaitID(t, "zz-1", 20*time.Second); resp["id"] != "zz-1" {
		t.Fatalf("session/list response id = %v, want \"zz-1\"", resp["id"])
	}
	if n := countStats(t, stats, `"id":"abc"`); n != 0 {
		t.Fatalf("client id \"abc\" leaked to devin (%d lines)", n)
	}
	if n := countStats(t, stats, `"id":"zz-1"`); n != 0 {
		t.Fatalf("client id \"zz-1\" leaked to devin (%d lines)", n)
	}
}

// (c) attach replays journaled events past afterSeq with ascending _seq —
// including after the original client is gone.
func TestAttachReplaysJournal(t *testing.T) {
	setupHub(t, fakeDevin(t))
	srv := newTestServer(t)

	a := dialClient(t, srv)
	sid := initAndNew(t, a)
	a.request(t, 9, "session/prompt", rpcMsg{
		"sessionId": sid,
		"prompt":    []any{rpcMsg{"type": "text", "text": "hi"}},
	})
	a.awaitID(t, 9, 20*time.Second) // turn finished; journal holds the run
	a.conn.CloseNow()

	b := dialClient(t, srv)
	b.request(t, 1, "_devingo/attach", rpcMsg{"sessionId": sid, "afterSeq": 0})
	resp := b.awaitID(t, 1, 20*time.Second)
	res, _ := resp["result"].(rpcMsg)
	if res["needLoad"] == true {
		t.Fatalf("attach returned needLoad for a hosted session: %v", res)
	}
	lastSeq, _ := res["lastSeq"].(float64)
	if lastSeq < 5 {
		t.Fatalf("lastSeq = %v, want >= 5 (new resp + 3 updates + prompt resp)", lastSeq)
	}

	var seqs []float64
	var updates, wrapped int
	var reqMethods []string
	for uint64(lastSeq) > uint64(len(seqs)) {
		m := b.next(t, 10*time.Second)
		seq, _ := m["_seq"].(float64)
		seqs = append(seqs, seq)
		switch m["method"] {
		case "session/update":
			updates++
			p, _ := m["params"].(rpcMsg)
			if p["sessionId"] != sid {
				t.Fatalf("replayed update for wrong session: %v", p["sessionId"])
			}
		case "_devingo/event":
			wrapped++
			p, _ := m["params"].(rpcMsg)
			rm, _ := p["reqMethod"].(string)
			reqMethods = append(reqMethods, rm)
		}
	}
	for i := 1; i < len(seqs); i++ {
		if seqs[i] <= seqs[i-1] {
			t.Fatalf("replayed _seq not ascending: %v", seqs)
		}
	}
	if updates != 3 {
		t.Fatalf("replayed %d session/update frames, want 3", updates)
	}
	if wrapped < 2 {
		t.Fatalf("expected >=2 _devingo/event wraps (new+prompt results), got %d", wrapped)
	}
	// The wrap must name the answered request — subscribers confine
	// turn-end handling to session/prompt results (bogus ends wedge
	// running off mid-turn and fire false done-notifications).
	want := map[string]bool{"session/new": true, "session/prompt": true}
	for _, rm := range reqMethods {
		delete(want, rm)
	}
	if len(want) > 0 {
		t.Fatalf("wrapped reqMethods %v missing %v", reqMethods, want)
	}
}

// (c2) subscribers see the authoritative turn state on each prompt-flight
// edge: _devingo/turn_running true at forward, false at the response. The
// marker is live-only (no _seq) — attach already carries the same state in
// its result, and a journaled seq pre-signal clients never ack would read
// as a gap and force a re-attach per edge. `!` user-shell prompts aren't
// turns: they emit no marker at all.
func TestTurnRunningBroadcast(t *testing.T) {
	setupHub(t, fakeDevin(t))
	srv := newTestServer(t)

	a := dialClient(t, srv)
	sid := initAndNew(t, a)
	b := dialClient(t, srv)
	b.request(t, 1, "_devingo/attach", rpcMsg{"sessionId": sid})
	b.awaitID(t, 1, 20*time.Second)

	a.request(t, 9, "session/prompt", rpcMsg{
		"sessionId": sid,
		"prompt":    []any{rpcMsg{"type": "text", "text": "hi"}},
	})
	a.awaitID(t, 9, 20*time.Second) // turn finished

	start := b.awaitMethod(t, "_devingo/turn_running", 20*time.Second)
	if p, _ := start["params"].(rpcMsg); p["running"] != true {
		t.Fatalf("first turn_running params = %v, want running=true", p)
	}
	end := b.awaitMethod(t, "_devingo/turn_running", 20*time.Second)
	if p, _ := end["params"].(rpcMsg); p["running"] != false {
		t.Fatalf("second turn_running params = %v, want running=false", p)
	}
	// Live-only: a journaled _seq would force pre-signal clients into a
	// re-attach at every turn edge.
	if start["_seq"] != nil || end["_seq"] != nil {
		t.Fatal("turn_running frames must not carry _seq (live-only)")
	}

	// Sequential turns repeat the edge pair.
	a.request(t, 10, "session/prompt", rpcMsg{
		"sessionId": sid,
		"prompt":    []any{rpcMsg{"type": "text", "text": "again"}},
	})
	a.awaitID(t, 10, 20*time.Second)
	for _, want := range []bool{true, false} {
		m := b.awaitMethod(t, "_devingo/turn_running", 20*time.Second)
		if p, _ := m["params"].(rpcMsg); p["running"] != want {
			t.Fatalf("turn_running params = %v, want running=%v", p, want)
		}
	}

	// `!` shell prompts keep no running count — after its flight the only
	// markers on the wire are the real prompt's true+false pair.
	a.request(t, 11, "session/prompt", rpcMsg{
		"sessionId": sid,
		"prompt": []any{rpcMsg{"type": "text", "text": "!ls",
			"_meta": rpcMsg{"cognition.ai/userShellCommand": true}}},
	})
	a.awaitID(t, 11, 20*time.Second)
	a.request(t, 12, "session/prompt", rpcMsg{
		"sessionId": sid,
		"prompt":    []any{rpcMsg{"type": "text", "text": "real"}},
	})
	a.awaitID(t, 12, 20*time.Second)
	var runs []any
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		m, ok := b.nextWithin(time.Until(deadline))
		if !ok {
			break
		}
		if m["method"] == "_devingo/turn_running" {
			p, _ := m["params"].(rpcMsg)
			runs = append(runs, p["running"])
		}
	}
	if len(runs) != 2 || runs[0] != true || runs[1] != false {
		t.Fatalf("markers after shell+real prompts = %v, want [true false] (real prompt only)", runs)
	}
}

// (d) a permission request reaches every subscriber; the first reply wins,
// late replies are dropped, and other clients see permission_resolved.
func TestPermissionFirstReplyWins(t *testing.T) {
	stats := setupHub(t, fakeDevin(t))
	srv := newTestServer(t)

	a := dialClient(t, srv)
	sid := initAndNew(t, a)

	b := dialClient(t, srv)
	b.request(t, 1, "_devingo/attach", rpcMsg{"sessionId": sid})
	b.awaitID(t, 1, 20*time.Second)

	a.request(t, 9, "session/prompt", rpcMsg{
		"sessionId": sid,
		"prompt":    []any{rpcMsg{"type": "text", "text": "PERM please"}},
	})

	permA := a.awaitMethod(t, "session/request_permission", 20*time.Second)
	permB := b.awaitMethod(t, "session/request_permission", 20*time.Second)
	idA, idB := fmt.Sprint(permA["id"]), fmt.Sprint(permB["id"])
	if idA != idB {
		t.Fatalf("permission request reached subscribers with different ids: %v vs %v", idA, idB)
	}
	if permA["_seq"] == nil || permB["_seq"] == nil {
		t.Fatal("permission request missing _seq")
	}

	// A answers first; devin must see exactly one response (to its id 910).
	a.send(t, rpcMsg{"jsonrpc": "2.0", "id": permA["id"], "result": rpcMsg{
		"outcome": rpcMsg{"outcome": "selected", "optionId": "o1"},
	}})
	resolved := b.awaitMethod(t, "_devingo/permission_resolved", 20*time.Second)
	params, _ := resolved["params"].(rpcMsg)
	if params["outcome"] != "selected" {
		t.Fatalf("resolved outcome = %v, want selected", params["outcome"])
	}
	if fmt.Sprint(params["requestId"]) != idA {
		t.Fatalf("resolved requestId = %v, want %v", params["requestId"], idA)
	}
	a.awaitID(t, 9, 20*time.Second) // prompt result still arrives

	// B's late reply must be dropped, not forwarded to devin.
	b.send(t, rpcMsg{"jsonrpc": "2.0", "id": permB["id"], "result": rpcMsg{
		"outcome": rpcMsg{"outcome": "cancelled"},
	}})
	time.Sleep(300 * time.Millisecond)
	if n := countStats(t, stats, `"id":910`); n != 1 {
		t.Fatalf("devin received %d permission replies, want 1", n)
	}
}

// An unanswered permission is cancelled by the gateway at perm-ttl — devin
// gets a cancelled reply and subscribers hear the resolution.
func TestPermissionTimeout(t *testing.T) {
	stats := setupHub(t, fakeDevin(t))
	oldTTL := *permTTL
	*permTTL = 400 * time.Millisecond
	t.Cleanup(func() { *permTTL = oldTTL })
	srv := newTestServer(t)

	a := dialClient(t, srv)
	sid := initAndNew(t, a)
	a.request(t, 9, "session/prompt", rpcMsg{
		"sessionId": sid,
		"prompt":    []any{rpcMsg{"type": "text", "text": "PERM"}},
	})
	a.awaitMethod(t, "session/request_permission", 20*time.Second)

	resolved := a.awaitMethod(t, "_devingo/permission_resolved", 20*time.Second)
	params, _ := resolved["params"].(rpcMsg)
	if params["outcome"] != "timeout" {
		t.Fatalf("resolved outcome = %v, want timeout", params["outcome"])
	}
	deadline := time.Now().Add(5 * time.Second)
	for countStats(t, stats, `"id":910`) == 0 && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
	}
	s := readStats(t, stats)
	if !strings.Contains(s, `"id":910`) || !strings.Contains(s, "cancelled") {
		t.Fatalf("devin never got the gateway's cancelled reply:\n%s", s)
	}
}

// A truncated journal must report needLoad even for afterSeq==0: the
// client is asking for events from seq 1 that the ring no longer holds.
// An intact journal (earliestSeq == 1) still replays from scratch.
func TestAttachNeedLoadOnTruncatedJournal(t *testing.T) {
	setupHub(t, fakeDevin(t))
	srv := newTestServer(t)

	a := dialClient(t, srv)
	sid := initAndNew(t, a)
	a.request(t, 9, "session/prompt", rpcMsg{
		"sessionId": sid,
		"prompt":    []any{rpcMsg{"type": "text", "text": "hi"}},
	})
	a.awaitID(t, 9, 20*time.Second)
	a.conn.CloseNow()

	// Simulate ring eviction: drop all but the last entries so
	// earliestSeq() > 1 (what the byte/entry caps would leave behind).
	bridgeHub.mu.Lock()
	s := bridgeHub.sessions[sid]
	for len(s.journal) > 2 {
		s.jBytes -= len(s.journal[0].frame)
		s.journal = s.journal[1:]
	}
	earliest := s.earliestSeq()
	bridgeHub.mu.Unlock()
	if earliest <= 1 {
		t.Fatalf("test setup: earliestSeq = %d, want > 1", earliest)
	}

	b := dialClient(t, srv)
	b.request(t, 1, "_devingo/attach", rpcMsg{"sessionId": sid, "afterSeq": 0})
	resp := b.awaitID(t, 1, 20*time.Second)
	res, _ := resp["result"].(rpcMsg)
	if res["needLoad"] != true {
		t.Fatalf("attach afterSeq=0 on truncated journal: needLoad=%v, want true (%v)", res["needLoad"], res)
	}
	if res["lastSeq"] == nil {
		t.Fatalf("needLoad response missing lastSeq: %v", res)
	}

	// A gap behind the ring's oldest entry is needLoad too.
	b.request(t, 2, "_devingo/attach", rpcMsg{"sessionId": sid, "afterSeq": earliest - 2})
	res = mustAttachResult(t, b, 2)
	if res["needLoad"] != true {
		t.Fatalf("attach afterSeq=%d (journal starts at %d): needLoad=%v, want true",
			earliest-2, earliest, res["needLoad"])
	}

	// Fresh, intact journal: afterSeq=0 replays everything.
	c := dialClient(t, srv)
	sid2 := initAndNew(t, c)
	b.request(t, 3, "_devingo/attach", rpcMsg{"sessionId": sid2, "afterSeq": 0})
	res = mustAttachResult(t, b, 3)
	if res["needLoad"] == true {
		t.Fatalf("attach afterSeq=0 on intact journal: needLoad=true, want replay")
	}
}

func mustAttachResult(t *testing.T, tc *testClient, id any) rpcMsg {
	t.Helper()
	resp := tc.awaitID(t, id, 20*time.Second)
	res, _ := resp["result"].(rpcMsg)
	if res == nil {
		t.Fatalf("attach id=%v: no result: %v", id, resp)
	}
	return res
}

// A cursor past the journal's high-water mark references a dead epoch —
// the session was idle-evicted and lazily re-created (nextSeq restarted
// at 1), or the bridge restarted. Serving it would replay nothing and
// silently diverge, so attach must answer needLoad.
func TestAttachNeedLoadOnJournalEpochReset(t *testing.T) {
	setupHub(t, fakeDevin(t))
	srv := newTestServer(t)

	// A fresh journal epoch: the session is hosted but its journal only
	// just restarted — the client's cursor of 7 belongs to the dead one.
	// lastInfo marks the session as hub-hosted; without it attach's
	// foreign-session guard would answer needLoad on the fresh attach too.
	bridgeHub.mu.Lock()
	s := bridgeHub.getSessionLocked("EPOCH-SID", "")
	s.lastInfo = json.RawMessage(`{"sessionId":"EPOCH-SID"}`)
	bridgeHub.mu.Unlock()

	c := dialClient(t, srv)
	c.request(t, 1, "_devingo/attach", rpcMsg{"sessionId": "EPOCH-SID", "afterSeq": 7})
	res := mustAttachResult(t, c, 1)
	if res["needLoad"] != true {
		t.Fatalf("stale-epoch attach: needLoad=%v, want true (%v)", res["needLoad"], res)
	}

	// A cursor inside the live epoch still attaches normally.
	c.request(t, 2, "_devingo/attach", rpcMsg{"sessionId": "EPOCH-SID", "afterSeq": 0})
	res = mustAttachResult(t, c, 2)
	if res["needLoad"] == true {
		t.Fatalf("fresh attach on restarted journal: needLoad=true (%v)", res)
	}
}

// Killing the resident process must error in-flight client requests,
// resolve pending agent requests as agent_died, journal + broadcast
// _devingo/agent_dead, and lazily respawn on the next demand.
func TestProcDeathErrorsInflightAndRespawns(t *testing.T) {
	stats := setupHub(t, fakeDevin(t))
	srv := newTestServer(t)

	a := dialClient(t, srv)
	sid := initAndNew(t, a)

	// One request the stub never answers stays in-flight…
	a.request(t, 77, "test/hang", rpcMsg{"sessionId": sid})
	// …and a permission leaves an agent->client request pending.
	a.request(t, 9, "session/prompt", rpcMsg{
		"sessionId": sid,
		"prompt":    []any{rpcMsg{"type": "text", "text": "PERM"}},
	})
	a.awaitMethod(t, "session/request_permission", 20*time.Second)
	// The stub completes prompts instantly; consume the normal result
	// before the kill so only id=77 is genuinely stuck.
	if resp := a.awaitID(t, 9, 20*time.Second); resp["result"] == nil {
		t.Fatalf("prompt result before kill: %v", resp)
	}

	bridgeHub.mu.Lock()
	p := bridgeHub.procs[""]
	bridgeHub.mu.Unlock()
	killProcessTree(p.cmd)

	// In-flight client requests are errored, never requeued or replayed.
	if resp := a.awaitID(t, 77, 20*time.Second); resp["error"] == nil {
		t.Fatalf("in-flight request after proc death got no error: %v", resp)
	}

	// The pending permission resolves agent_died; the session gets a
	// journaled agent_dead marker pushed live to subscribers.
	resolved := a.awaitMethod(t, "_devingo/permission_resolved", 20*time.Second)
	params, _ := resolved["params"].(rpcMsg)
	if params["outcome"] != "agent_died" {
		t.Fatalf("pending permission outcome = %v, want agent_died", params["outcome"])
	}
	dead := a.awaitMethod(t, "_devingo/agent_dead", 20*time.Second)
	dparams, _ := dead["params"].(rpcMsg)
	if dparams["sessionId"] != sid {
		t.Fatalf("agent_dead sessionId = %v, want %s", dparams["sessionId"], sid)
	}
	if dead["_seq"] == nil {
		t.Fatal("agent_dead marker missing _seq (not journaled)")
	}

	bridgeHub.mu.Lock()
	pending := len(bridgeHub.sessions[sid].pending)
	bridgeHub.mu.Unlock()
	if pending != 0 {
		t.Fatalf("session still has %d pending agent requests after death", pending)
	}

	// The next demand lazily respawns the resident process and is served.
	a.request(t, 88, "session/list", rpcMsg{})
	resp := a.awaitID(t, 88, 30*time.Second)
	if resp["error"] != nil || resp["result"] == nil {
		t.Fatalf("post-death session/list did not complete: %v", resp)
	}
	if n := countStats(t, stats, `"method":"initialize"`); n != 2 {
		t.Fatalf("devin saw %d initialize calls, want 2 (original + respawn)", n)
	}
}

// _devingo/sessions after a failed first handshake must be served by the
// healthy respawn — the retry used to run off the end of the loop and
// answer "devin process unavailable" anyway.
func TestSessionListServedAfterRespawn(t *testing.T) {
	stats := setupHub(t, fakeDevin(t))
	// First boot exits before answering (flag file created); the respawn
	// sees the file and behaves normally.
	t.Setenv("DEVINGO_DIE_ONCE", filepath.Join(t.TempDir(), "die-once"))
	srv := newTestServer(t)

	c := dialClient(t, srv)
	c.request(t, 1, "_devingo/sessions", rpcMsg{})
	resp := c.awaitID(t, 1, 60*time.Second)
	if resp["error"] != nil {
		t.Fatalf("_devingo/sessions after respawn: %v", resp["error"])
	}
	res, _ := resp["result"].(rpcMsg)
	if _, ok := res["sessions"].([]any); !ok {
		t.Fatalf("_devingo/sessions result missing sessions array: %v", res)
	}
	// The dead first boot never read a line; only the respawn serves.
	if n := countStats(t, stats, `"method":"session/list"`); n != 1 {
		t.Fatalf("devin saw %d session/list calls, want 1 (respawn only)", n)
	}
}

// Regression: _devingo/sessions with no cwd/search must still forward a
// params OBJECT — devin answers -32602 when the field is absent/null
// ("invalid type: null, expected struct ListSessionsRequest").
func TestSessionListAlwaysSendsParamsObject(t *testing.T) {
	stats := setupHub(t, fakeDevin(t))
	srv := newTestServer(t)

	c := dialClient(t, srv)
	c.request(t, 1, "_devingo/sessions", rpcMsg{})
	if resp := c.awaitID(t, 1, 60*time.Second); resp["error"] != nil {
		t.Fatalf("_devingo/sessions: %v", resp["error"])
	}
	// Stats carry raw request lines; the frame's keys marshal sorted, so
	// "method":"session/list","params":{ must appear verbatim.
	if n := countStats(t, stats, `"method":"session/list","params":{`); n != 1 {
		t.Fatalf("session/list params was not an object:\n%s", readStats(t, stats))
	}
}

// _devingo/detach must actually unsubscribe: no more session traffic, and
// the hub's sub set shrinks.
func TestDetachRemovesSubscription(t *testing.T) {
	setupHub(t, fakeDevin(t))
	srv := newTestServer(t)

	a := dialClient(t, srv)
	sid := initAndNew(t, a)

	b := dialClient(t, srv)
	b.request(t, 1, "_devingo/attach", rpcMsg{"sessionId": sid})
	b.awaitID(t, 1, 20*time.Second)
	b.request(t, 2, "_devingo/detach", rpcMsg{"sessionId": sid})
	if resp := b.awaitID(t, 2, 20*time.Second); resp["error"] != nil {
		t.Fatalf("detach: %v", resp["error"])
	}
	b.drain(300 * time.Millisecond) // discard the attach replay backlog

	bridgeHub.mu.Lock()
	n := len(bridgeHub.sessions[sid].subs)
	bridgeHub.mu.Unlock()
	if n != 1 {
		t.Fatalf("session has %d subscribers after detach, want 1", n)
	}

	a.request(t, 9, "session/prompt", rpcMsg{
		"sessionId": sid,
		"prompt":    []any{rpcMsg{"type": "text", "text": "hello"}},
	})
	a.awaitMethod(t, "session/update", 20*time.Second) // broadcast reached a sub
	if m, ok := b.nextWithin(500 * time.Millisecond); ok {
		t.Fatalf("detached client received session traffic: %v", m)
	}
	a.awaitID(t, 9, 20*time.Second)
}

// A session with nothing left to keep (no subs/running/pending/loaders)
// is evicted at idle-ttl and released devin-side via session/close.
func TestIdleTTLEvictsSession(t *testing.T) {
	stats := setupHub(t, fakeDevin(t))
	oldTTL := *idleTTL
	*idleTTL = 300 * time.Millisecond
	t.Cleanup(func() { *idleTTL = oldTTL })
	srv := newTestServer(t)

	a := dialClient(t, srv)
	sid := initAndNew(t, a)
	a.request(t, 3, "_devingo/detach", rpcMsg{"sessionId": sid})
	a.awaitID(t, 3, 20*time.Second)

	waitSessionGone(t, sid)
	// The devin-side lock is released via session/close.
	deadline := time.Now().Add(5 * time.Second)
	for countStats(t, stats, `"method":"session/close"`) == 0 && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
	}
	if countStats(t, stats, `"method":"session/close"`) == 0 {
		t.Fatalf("session/close never reached devin:\n%s", readStats(t, stats))
	}
}

// A session created lazily by foreign-sid traffic (nobody ever
// subscribed) must still get the idle timer — it evicts like any other.
func TestLazySessionArmsIdleTTL(t *testing.T) {
	setupHub(t, fakeDevin(t))
	oldTTL := *idleTTL
	*idleTTL = 300 * time.Millisecond
	t.Cleanup(func() { *idleTTL = oldTTL })
	srv := newTestServer(t)

	a := dialClient(t, srv)
	a.request(t, 1, "initialize", rpcMsg{"protocolVersion": 1})
	a.awaitID(t, 1, 20*time.Second)
	a.request(t, 2, "test/ghost", rpcMsg{})
	a.awaitID(t, 2, 20*time.Second)

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		bridgeHub.mu.Lock()
		_, ok := bridgeHub.sessions["GHOST-SESSION"]
		bridgeHub.mu.Unlock()
		if ok {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	bridgeHub.mu.Lock()
	_, hosted := bridgeHub.sessions["GHOST-SESSION"]
	bridgeHub.mu.Unlock()
	if !hosted {
		t.Fatal("ghost session never got hosted")
	}
	waitSessionGone(t, "GHOST-SESSION")
}

func waitSessionGone(t *testing.T, sid string) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		bridgeHub.mu.Lock()
		_, ok := bridgeHub.sessions[sid]
		bridgeHub.mu.Unlock()
		if !ok {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("session %s still hosted after idle ttl", sid)
}

// While another client's session/load owns the delivery pool, subscribers
// miss whatever the journal absorbs. When the load finishes the gateway
// journals + pushes _devingo/session_resync so they re-attach and replay
// the gap — the loader itself is not told (it holds the full replay).
func TestSessionLoadResyncsSubscribers(t *testing.T) {
	setupHub(t, fakeDevin(t))
	srv := newTestServer(t)

	a := dialClient(t, srv)
	sid := initAndNew(t, a) // a is subscribed

	b := dialClient(t, srv)
	b.request(t, 1, "session/load", rpcMsg{"sessionId": sid})
	b.awaitID(t, 1, 20*time.Second)

	resync := a.awaitMethod(t, "_devingo/session_resync", 20*time.Second)
	params, _ := resync["params"].(rpcMsg)
	if params["sessionId"] != sid {
		t.Fatalf("resync sessionId = %v, want %s", params["sessionId"], sid)
	}
	if resync["_seq"] == nil {
		t.Fatal("session_resync missing _seq (not journaled)")
	}

	// The (former) loader never sees the resync notice.
	deadline := time.Now().Add(400 * time.Millisecond)
	for time.Now().Before(deadline) {
		m, ok := b.nextWithin(time.Until(deadline))
		if !ok {
			break
		}
		if m["method"] == "_devingo/session_resync" {
			t.Fatalf("loader received session_resync: %v", m)
		}
	}

	// The marker is journaled: a fresh attach replays it.
	c := dialClient(t, srv)
	c.request(t, 1, "_devingo/attach", rpcMsg{"sessionId": sid, "afterSeq": 0})
	res := mustAttachResult(t, c, 1)
	lastSeq, _ := res["lastSeq"].(float64)
	found := false
	for i := 0; i < int(lastSeq); i++ {
		if m := c.next(t, 10*time.Second); m["method"] == "_devingo/session_resync" {
			found = true
		}
	}
	if !found {
		t.Fatal("session_resync absent from journal replay")
	}
}

// Internal request ids are sequential and guessable — a client outside a
// session's audience must not consume or resolve its pending requests.
func TestForeignClientCannotAnswerPermission(t *testing.T) {
	stats := setupHub(t, fakeDevin(t))
	srv := newTestServer(t)

	a := dialClient(t, srv)
	sid := initAndNew(t, a)

	stranger := dialClient(t, srv) // connected, never attaches

	a.request(t, 9, "session/prompt", rpcMsg{
		"sessionId": sid,
		"prompt":    []any{rpcMsg{"type": "text", "text": "PERM"}},
	})
	perm := a.awaitMethod(t, "session/request_permission", 20*time.Second)
	a.awaitID(t, 9, 20*time.Second) // stub answers the prompt immediately
	a.drain(300 * time.Millisecond) // consume the prompt's held chunks

	// A second subscriber observes (non-)resolution; the resolved notice
	// excludes the responder, so a itself can't see it.
	w := dialClient(t, srv)
	w.request(t, 1, "_devingo/attach", rpcMsg{"sessionId": sid})
	w.awaitID(t, 1, 20*time.Second)
	w.drain(300 * time.Millisecond)

	// The forged answer is dropped: no resolution broadcast, no reply to
	// devin, and the request stays pending for the real audience.
	stranger.send(t, rpcMsg{"jsonrpc": "2.0", "id": perm["id"], "result": rpcMsg{
		"outcome": rpcMsg{"outcome": "cancelled"},
	}})
	if m, ok := w.nextWithin(400 * time.Millisecond); ok {
		t.Fatalf("stranger's answer produced session traffic: %v", m)
	}
	if n := countStats(t, stats, `"id":910`); n != 0 {
		t.Fatalf("stranger's answer reached devin (%d replies)", n)
	}
	bridgeHub.mu.Lock()
	_, stillPending := bridgeHub.sessions[sid].pending[int64(perm["id"].(float64))]
	bridgeHub.mu.Unlock()
	if !stillPending {
		t.Fatal("stranger's answer consumed the pending permission")
	}

	// The real audience can still answer.
	a.send(t, rpcMsg{"jsonrpc": "2.0", "id": perm["id"], "result": rpcMsg{
		"outcome": rpcMsg{"outcome": "selected", "optionId": "o1"},
	}})
	resolved := w.awaitMethod(t, "_devingo/permission_resolved", 20*time.Second)
	params, _ := resolved["params"].(rpcMsg)
	if params["outcome"] != "selected" {
		t.Fatalf("resolved outcome = %v, want selected", params["outcome"])
	}
	deadline := time.Now().Add(5 * time.Second)
	for countStats(t, stats, `"id":910`) == 0 && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
	}
	if n := countStats(t, stats, `"id":910`); n != 1 {
		t.Fatalf("devin received %d permission replies, want 1", n)
	}
}

// A frame arriving while the post-ready drain is still flushing the
// backlog must queue behind it — a direct dispatch would overtake the
// queued ops on devin's stdin (e.g. a prompt landing before the queued
// session/new that hosts it).
func TestMidDrainFrameQueuesBehindBacklog(t *testing.T) {
	stats := setupHub(t, fakeDevin(t))
	// Bring the resident process up and fully ready (no ws client needed).
	if _, err := bridgeHub.callSessionList("", ""); err != nil {
		t.Fatalf("warm-up session/list: %v", err)
	}

	bridgeHub.mu.Lock()
	p := bridgeHub.procs[""]
	// Fake the mid-drain window: one op still queued, drain in progress.
	c1 := &Client{hub: bridgeHub, send: make(chan []byte, 8), sessions: map[*Session]bool{}}
	op1 := []byte(`{"jsonrpc":"2.0","id":10,"method":"test/first"}`)
	head1, _ := rpcParse(op1)
	p.queue = append(p.queue, queuedOp{client: c1, data: op1, head: head1})
	p.draining = true
	bridgeHub.mu.Unlock()

	// The mid-drain arrival must enqueue behind the backlog, not write.
	c2 := &Client{hub: bridgeHub, send: make(chan []byte, 8), sessions: map[*Session]bool{}}
	bridgeHub.handleFrame(c2, []byte(`{"jsonrpc":"2.0","id":20,"method":"test/second"}`))
	bridgeHub.mu.Lock()
	qlen := len(p.queue)
	bridgeHub.mu.Unlock()
	if qlen != 2 {
		t.Fatalf("mid-drain frame did not queue behind the backlog (queue len %d)", qlen)
	}

	// Finish the drain the way markReady does; stdin order must match
	// the queue order.
	bridgeHub.mu.Lock()
	ops := p.queue
	p.queue = nil
	p.draining = false
	bridgeHub.mu.Unlock()
	for _, op := range ops {
		bridgeHub.dispatchReady(p, op.client, op.data, op.head)
	}

	deadline := time.Now().Add(10 * time.Second)
	for !strings.Contains(readStats(t, stats), "test/second") && time.Now().Before(deadline) {
		time.Sleep(25 * time.Millisecond)
	}
	s := readStats(t, stats)
	i1 := strings.Index(s, `"method":"test/first"`)
	i2 := strings.Index(s, `"method":"test/second"`)
	if i1 < 0 || i2 < 0 {
		t.Fatalf("stats missing drain frames (first@%d second@%d):\n%s", i1, i2, s)
	}
	if i1 > i2 {
		t.Fatalf("mid-drain frame overtook the queued op on devin's stdin:\n%s", s)
	}
}

// A request line still buffered in devin's stdout when the process dies
// must not register: procDied already ran, so nothing would ever resolve
// or route it — pendingAgent/timer/journal would all leak onto a corpse.
func TestPostDeathAgentRequestDropped(t *testing.T) {
	setupHub(t, fakeDevin(t))
	srv := newTestServer(t)

	a := dialClient(t, srv)
	sid := initAndNew(t, a)

	bridgeHub.mu.Lock()
	p := bridgeHub.procs[""]
	bridgeHub.mu.Unlock()
	killProcessTree(p.cmd)
	a.awaitMethod(t, "_devingo/agent_dead", 20*time.Second) // procDied done
	a.drain(300 * time.Millisecond)

	bridgeHub.mu.Lock()
	journalLen := len(bridgeHub.sessions[sid].journal)
	bridgeHub.mu.Unlock()

	// The dead process's readLoop can still surface a buffered line.
	bridgeHub.onDevinLine(p, []byte(`{"jsonrpc":"2.0","id":55,"method":"session/request_permission","params":{"sessionId":"`+sid+`"}}`))

	bridgeHub.mu.Lock()
	agent := len(bridgeHub.pendingAgent)
	sess := len(bridgeHub.sessions[sid].pending)
	journal := len(bridgeHub.sessions[sid].journal)
	bridgeHub.mu.Unlock()
	if agent != 0 || sess != 0 {
		t.Fatalf("zombie request registered: pendingAgent=%d session.pending=%d", agent, sess)
	}
	if journal != journalLen {
		t.Fatalf("zombie request was journaled (%d -> %d entries)", journalLen, journal)
	}
	if m, ok := a.nextWithin(400 * time.Millisecond); ok {
		t.Fatalf("zombie permission reached the client: %v", m)
	}
}

// Offline push (opt-in): with an endpoint configured, a permission nobody
// can see pings "有新审批" once and the turn ending pings "任务完成" —
// while a watching subscriber keeps both silent.
func TestOfflinePushFiresWhenUnwatched(t *testing.T) {
	var mu sync.Mutex
	var bodies []string
	pushSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		mu.Lock()
		bodies = append(bodies, string(b))
		mu.Unlock()
		w.WriteHeader(http.StatusOK)
	}))
	t.Cleanup(pushSrv.Close)
	oldPush := bridgePush
	t.Cleanup(func() { bridgePush = oldPush })
	setupHub(t, fakeDevin(t))
	bridgePush = newPushNotifier(pushSrv.URL)
	srv := newTestServer(t)

	a := dialClient(t, srv)
	sid := initAndNew(t, a) // a is subscribed -> the session has an audience

	// A watched turn produces no push at all.
	a.request(t, 9, "session/prompt", rpcMsg{
		"sessionId": sid,
		"prompt":    []any{rpcMsg{"type": "text", "text": "hi"}},
	})
	a.awaitID(t, 9, 20*time.Second)
	time.Sleep(200 * time.Millisecond) // push POSTs are async; give them a window
	mu.Lock()
	if len(bodies) != 0 {
		t.Fatalf("push fired while a subscriber watched: %v", bodies)
	}
	mu.Unlock()

	// Detach, then run a PERM turn: the permission pings once (first pending
	// with no audience) and the turn-end pings — nothing else may arrive.
	a.request(t, 3, "_devingo/detach", rpcMsg{"sessionId": sid})
	a.awaitID(t, 3, 20*time.Second)
	a.request(t, 10, "session/prompt", rpcMsg{
		"sessionId": sid,
		"prompt":    []any{rpcMsg{"type": "text", "text": "PERM"}},
	})
	a.awaitID(t, 10, 20*time.Second)

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		mu.Lock()
		n := len(bodies)
		mu.Unlock()
		if n >= 2 {
			break
		}
		time.Sleep(25 * time.Millisecond)
	}
	mu.Lock()
	seen := map[string]bool{}
	for _, b := range bodies {
		seen[b] = true
	}
	n := len(bodies)
	mu.Unlock()
	if n != 2 || !seen["有新审批"] || !seen["任务完成"] {
		t.Fatalf("push bodies = %v, want exactly {有新审批, 任务完成}", bodies)
	}

	// The unwatched permission still pends for a late-attaching client.
	bridgeHub.mu.Lock()
	pend := len(bridgeHub.sessions[sid].pending)
	bridgeHub.mu.Unlock()
	if pend != 1 {
		t.Fatalf("pending approvals = %d, want 1", pend)
	}
}

// The hosted-session cap evictor must treat an in-flight session/load as
// busy — same as the idle-ttl check does.
func TestSessionCapSkipsLoadingSessions(t *testing.T) {
	setupHub(t, fakeDevin(t))

	bridgeHub.mu.Lock()
	for i := 0; i < maxHostedSessions; i++ {
		sid := fmt.Sprintf("FILL-%02d", i)
		bridgeHub.sessions[sid] = &Session{
			id:        sid,
			subs:      map[*Client]bool{},
			loaders:   map[*Client]int{},
			pending:   map[int64]*agentReq{},
			nextSeq:   1,
			idleSince: time.Now().Add(-time.Hour),
		}
	}
	// FILL-00 is mid-load: oldest, but must not be evicted.
	bridgeHub.sessions["FILL-00"].loaders[&Client{}] = 1
	bridgeHub.getSessionLocked("NEW-SID", "") // past the cap -> evicts oldest idle
	_, loadingKept := bridgeHub.sessions["FILL-00"]
	_, newHosted := bridgeHub.sessions["NEW-SID"]
	total := len(bridgeHub.sessions)
	bridgeHub.mu.Unlock()

	if !loadingKept {
		t.Fatal("cap evictor dropped a session with an in-flight load")
	}
	if !newHosted {
		t.Fatal("triggering session was not hosted")
	}
	if total != maxHostedSessions {
		t.Fatalf("hub holds %d sessions, want %d (one idle evicted)", total, maxHostedSessions)
	}
}

// devin accepts a comma-separated refusal-fallback candidate list
// (devin-acp(1)) — each item must independently match the model charset.
func TestModelNameRe(t *testing.T) {
	for _, ok := range []string{
		"claude-opus-4.5", "gpt 5.2", "a",
		"model-a,model-b", "a,b,c/d:e+f",
	} {
		if !modelNameRe.MatchString(ok) {
			t.Errorf("modelNameRe rejected %q", ok)
		}
	}
	for _, bad := range []string{
		"", ",a", "a,", "a,,b", "a; rm -rf /", "a|b", "$(x)", " a",
	} {
		if modelNameRe.MatchString(bad) {
			t.Errorf("modelNameRe accepted %q", bad)
		}
	}
}

// W8.3: a session/new carrying the _meta spawn profile rides its own
// `devin acp` process — argv gets --agent-type, the env gets
// DEVIN_REFUSAL_FALLBACK, and session-scoped traffic routes back to it.
// Once nothing uses the profile its proc is reaped; the default resident
// process never spawns for profiled sessions.
func TestSpawnProfileRouting(t *testing.T) {
	stats := setupHub(t, fakeDevin(t))
	srv := newTestServer(t)
	a := dialClient(t, srv)

	meta := rpcMsg{
		"devingo/agentType":       "review",
		"devingo/refusalFallback": "test-model",
	}
	a.request(t, 1, "session/new", rpcMsg{"cwd": "C:/proj", "_meta": meta})
	resp := a.awaitID(t, 1, 20*time.Second)
	if resp["error"] != nil {
		t.Fatalf("profiled session/new: %v", resp["error"])
	}
	sid, _ := resp["result"].(rpcMsg)["sessionId"].(string)
	if sid == "" {
		t.Fatalf("profiled session/new result: %v", resp["result"])
	}

	// Exactly one proc, spawned with the profile; the default proc stays
	// unspawned — nothing asked for it.
	bridgeHub.mu.Lock()
	s := bridgeHub.sessions[sid]
	nprocs := len(bridgeHub.procs)
	def := bridgeHub.procs[""]
	bridgeHub.mu.Unlock()
	if nprocs != 1 || def != nil {
		t.Fatalf("procs = %d (default spawned: %v), want 1 profiled proc only", nprocs, def != nil)
	}
	wantKey := "review\x00test-model"
	if s == nil || s.procKey != wantKey {
		t.Fatalf("session procKey = %q, want %q", s.procKey, wantKey)
	}
	deadline := time.Now().Add(10 * time.Second)
	var boot string
	for time.Now().Before(deadline) {
		boot = readStats(t, stats)
		if strings.Contains(boot, "--agent-type review") {
			break
		}
		time.Sleep(25 * time.Millisecond)
	}
	if !strings.Contains(boot, "--agent-type review") || !strings.Contains(boot, "ENV_FB=test-model") {
		t.Fatalf("spawned proc missing profile args/env:\n%s", boot)
	}

	// Session-scoped traffic routes to the profiled proc (the stub logs
	// every inbound frame it sees — a prompt reaching devin proves the
	// routing ran on the only process alive).
	a.request(t, 2, "session/prompt", rpcMsg{
		"sessionId": sid,
		"prompt":    []any{rpcMsg{"type": "text", "text": "hi"}},
	})
	if resp := a.awaitID(t, 2, 20*time.Second); resp["error"] != nil {
		t.Fatalf("prompt on profiled session: %v", resp["error"])
	}

	// Same-profile session/new reuses the proc — still exactly one.
	a.request(t, 3, "session/new", rpcMsg{"cwd": "C:/proj", "_meta": meta})
	resp = a.awaitID(t, 3, 20*time.Second)
	sid2, _ := resp["result"].(rpcMsg)["sessionId"].(string)
	bridgeHub.mu.Lock()
	nprocs = len(bridgeHub.procs)
	bridgeHub.mu.Unlock()
	if resp["error"] != nil || sid2 == "" {
		t.Fatalf("second profiled session/new: %v", resp["error"])
	}
	if nprocs != 1 {
		t.Fatalf("same profile spawned a second proc (procs=%d)", nprocs)
	}

	// A bogus agent type is rejected -32602 before any spawn.
	a.request(t, 4, "session/new", rpcMsg{
		"cwd": "C:/proj", "_meta": rpcMsg{"devingo/agentType": "bogus"},
	})
	resp = a.awaitID(t, 4, 20*time.Second)
	if resp["error"] == nil {
		t.Fatal("bogus agentType was not rejected")
	}
	if code, _ := resp["error"].(rpcMsg)["code"].(float64); int(code) != -32602 {
		t.Fatalf("bogus agentType error code = %v, want -32602", resp["error"])
	}

	// Deleting both profiled sessions reaps the idle profile proc.
	for i, d := range map[int]string{5: sid, 6: sid2} {
		a.request(t, i, "session/delete", rpcMsg{"sessionId": d})
		if resp := a.awaitID(t, i, 20*time.Second); resp["error"] != nil {
			t.Fatalf("session/delete %s: %v", d, resp["error"])
		}
	}
	deadline = time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		bridgeHub.mu.Lock()
		nprocs = len(bridgeHub.procs)
		bridgeHub.mu.Unlock()
		if nprocs == 0 {
			break
		}
		time.Sleep(25 * time.Millisecond)
	}
	if nprocs != 0 {
		t.Fatalf("idle profile proc was not reaped (procs=%d)", nprocs)
	}
}

// M9.1: a takeover's force-close lazily creates the hub entry — the
// follow-up attach must answer needLoad so the client falls back to
// session/load instead of pinning to an empty journal.
func TestAttachAfterForeignCloseNeedsLoad(t *testing.T) {
	setupHub(t, fakeDevin(t))
	srv := newTestServer(t)
	a := dialClient(t, srv)

	a.request(t, 1, "session/close", rpcMsg{
		"sessionId": "FOREIGN",
		"_meta":     rpcMsg{"cognition.ai/force": true},
	})
	a.awaitID(t, 1, 20*time.Second)

	a.request(t, 2, "_devingo/attach", rpcMsg{"sessionId": "FOREIGN"})
	resp := a.awaitID(t, 2, 20*time.Second)
	res, _ := resp["result"].(rpcMsg)
	if res["needLoad"] != true {
		t.Fatalf("attach after foreign close needLoad = %v, want true", res)
	}
}

// M9.1: only the newest queued _devingo/sessions survives — the displaced
// waiter gets an explicit error instead of hanging until the app timeout.
// Also pins contentSearch → session/list content_search + excerpts param.
func TestSessionsSupersedeAnswersDisplaced(t *testing.T) {
	stats := setupHub(t, fakeDevin(t))
	srv := newTestServer(t)
	a := dialClient(t, srv)

	// The hanging query pins the per-client list slot.
	a.request(t, 1, "_devingo/sessions", rpcMsg{"contentSearch": "HANG"})
	deadline := time.Now().Add(20 * time.Second)
	for !strings.Contains(readStats(t, stats), `"content_search":"HANG"`) &&
		time.Now().Before(deadline) {
		time.Sleep(25 * time.Millisecond)
	}
	if !strings.Contains(readStats(t, stats), `"content_search":"HANG"`) {
		t.Fatalf("content_search never reached devin:\n%s", readStats(t, stats))
	}
	if !strings.Contains(readStats(t, stats), `"with_message_excerpts":true`) {
		t.Fatalf("with_message_excerpts missing:\n%s", readStats(t, stats))
	}

	// req2 queues behind the hang; req3 supersedes it → explicit error.
	a.request(t, 2, "_devingo/sessions", rpcMsg{"contentSearch": "x"})
	a.request(t, 3, "_devingo/sessions", rpcMsg{"contentSearch": "y"})
	resp := a.awaitID(t, 2, 20*time.Second)
	if resp["error"] == nil {
		t.Fatalf("superseded sessions request got no error: %v", resp)
	}
}

// The attach snapshot must track config changes — a stale lastInfo replays
// the session's creation-time mode/model and the client's UI snaps back.
// Covers both sync paths: the set_config_option response array and the
// current_mode_update notification.
func TestAttachSeesConfigChanges(t *testing.T) {
	setupHub(t, fakeDevin(t))
	srv := newTestServer(t)
	a := dialClient(t, srv)

	a.request(t, 1, "session/new", rpcMsg{"cwd": "C:/proj"})
	resp := a.awaitID(t, 1, 20*time.Second)
	sid, _ := resp["result"].(rpcMsg)["sessionId"].(string)
	if sid == "" {
		t.Fatalf("session/new: %v", resp)
	}

	// The snapshot carries both config shapes: the configOptions entry
	// (devin's surface) and the classic modes object (which the client
	// prefers when present). Both must stay synced.
	curModes := func(id int) (opt, modes string) {
		a.request(t, id, "_devingo/attach", rpcMsg{"sessionId": sid})
		res, _ := a.awaitID(t, id, 20*time.Second)["result"].(rpcMsg)
		sess, _ := res["session"].(rpcMsg)
		opts, _ := sess["configOptions"].([]any)
		for _, o := range opts {
			if om, _ := o.(rpcMsg); om["id"] == "mode" {
				opt, _ = om["currentValue"].(string)
			}
		}
		m, _ := sess["modes"].(rpcMsg)
		modes, _ = m["currentModeId"].(string)
		return opt, modes
	}
	check := func(id int, want, where string) {
		if opt, modes := curModes(id); opt != want || modes != want {
			t.Fatalf("%s: mode = %q/%q (configOptions/modes), want %q", where, opt, modes, want)
		}
	}
	check(2, "accept-edits", "baseline")

	// Response-side sync: set_config_option's configOptions array merges
	// into the snapshot served on the next attach.
	a.request(t, 3, "session/set_config_option", rpcMsg{
		"sessionId": sid, "configId": "mode", "value": "plan",
	})
	if resp := a.awaitID(t, 3, 20*time.Second); resp["error"] != nil {
		t.Fatalf("set_config_option: %v", resp["error"])
	}
	check(4, "plan", "after set_config_option")

	// Notification-side sync: the stub emits current_mode_update before
	// its result, so it has already been applied when awaitID returns.
	a.request(t, 5, "test/modeupdate", rpcMsg{"sessionId": sid})
	a.awaitID(t, 5, 20*time.Second)
	check(6, "ask", "after current_mode_update")
}
