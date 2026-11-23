// G1 multi-session probe: can ONE `devin acp` process host two concurrent
// sessions? Drives devin directly over stdio (the bridge just relays NDJSON,
// so this exercises the same wire protocol without a running bridge).
//
// Scenario:
//  1. process X: initialize + authenticate, then session/new twice (A, B).
//  2. session/prompt A and B concurrently — tasks that take >30s and need
//     tool calls + permission approvals.
//  3. While both stream: verify every session/update's sessionId; hold each
//     session/request_permission reply for a few seconds to see whether the
//     OTHER session stalls; session/cancel A and watch B; session/load a
//     third session while B still runs; then spawn process Y and try to
//     session/load A (expected: locked/busy error).
//
// Usage: go run ./acptest - "<any cwd>" multi
// Output: stdout; capture into probe-logs/11-multi-session.log.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

// How long each permission request is held before we reply "allow". Holding
// creates the observation window for cross-session blocking.
const permHold = 8 * time.Second

// Stage timeouts inside the scenario.
const (
	initTimeout    = 60 * time.Second
	authTimeout    = 90 * time.Second
	newTimeout     = 90 * time.Second
	promptDeadline = 4 * time.Minute // concurrent-prompt stage cap
	loadTimeout    = 60 * time.Second
)

// Fallback: cancel A if its first permission request hasn't triggered the
// cancel earlier (see onAPerm).
const cancelAfter = 30 * time.Second

// --- stdio acpProc ---------------------------------------------------------

type acpProc struct {
	name   string // log prefix, e.g. "X", "Y"
	cmd    *exec.Cmd
	pid    int
	stdin  io.WriteCloser
	writeM sync.Mutex
	inbox  chan rpcMsg
}

// resolveDevin mirrors the bridge's binary resolution: the versioned binary
// under %LOCALAPPDATA%\devin\cli\_versions\<newest>\bin\devin.exe wins over
// the PATH shim (the shim self-terminates children when its supervisor dies).
// DEVIN_BIN overrides.
func resolveDevin() string {
	if v := os.Getenv("DEVIN_BIN"); v != "" {
		return v
	}
	if runtime.GOOS == "windows" {
		if local := os.Getenv("LOCALAPPDATA"); local != "" {
			versionsDir := filepath.Join(local, "devin", "cli", "_versions")
			if entries, err := os.ReadDir(versionsDir); err == nil {
				newest := ""
				for _, e := range entries {
					if e.IsDir() && e.Name() > newest {
						newest = e.Name()
					}
				}
				if newest != "" {
					exe := filepath.Join(versionsDir, newest, "bin", "devin.exe")
					if _, err := os.Stat(exe); err == nil {
						return exe
					}
				}
			}
		}
	}
	return "devin"
}

// devinAPIKey mirrors the bridge's devinCredentialsKey: DEVIN_API_KEY env
// wins, else windsurf_api_key from %APPDATA%\devin\credentials.toml.
func devinAPIKey() string {
	if v := os.Getenv("DEVIN_API_KEY"); v != "" {
		return v
	}
	dir := os.Getenv("APPDATA")
	if dir == "" {
		if home, err := os.UserHomeDir(); err == nil {
			dir = filepath.Join(home, ".config")
		}
	}
	if dir == "" {
		return ""
	}
	b, err := os.ReadFile(filepath.Join(dir, "devin", "credentials.toml"))
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(b), "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "windsurf_api_key") {
			if parts := strings.SplitN(line, "=", 2); len(parts) == 2 {
				return strings.Trim(strings.TrimSpace(parts[1]), `"'`)
			}
		}
	}
	return ""
}

func spawnACP(cwd, name string) (*acpProc, error) {
	bin := resolveDevin()
	fmt.Printf("=== spawning devin acp as %s: %s (cwd %s)\n", name, bin, cwd)
	cmd := exec.Command(bin, "acp")
	cmd.Dir = cwd
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	p := &acpProc{
		name:  name,
		cmd:   cmd,
		pid:   cmd.Process.Pid,
		stdin: stdin,
		inbox: make(chan rpcMsg, 512),
	}
	fmt.Printf("=== %s devin acp started, pid %d\n", name, p.pid)
	go p.readLoop(stdout)
	go p.errLoop(stderr)
	return p, nil
}

func (p *acpProc) readLoop(stdout io.Reader) {
	defer close(p.inbox)
	br := bufio.NewReaderSize(stdout, 1<<20)
	for {
		line, err := br.ReadBytes('\n')
		s := strings.TrimRight(string(line), "\r\n")
		if s != "" {
			var m rpcMsg
			if json.Unmarshal([]byte(s), &m) == nil {
				p.inbox <- m
			}
		}
		if err != nil {
			return
		}
	}
}

func (p *acpProc) errLoop(stderr io.Reader) {
	br := bufio.NewReaderSize(stderr, 64<<10)
	for {
		line, err := br.ReadString('\n')
		line = strings.TrimRight(line, "\r\n")
		if line != "" {
			fmt.Printf("%s [stderr] %s\n", p.name, trunc(line, 400))
		}
		if err != nil {
			return
		}
	}
}

func (p *acpProc) sendLine(b []byte) {
	p.writeM.Lock()
	defer p.writeM.Unlock()
	fmt.Printf("%s >>> %s\n", p.name, trunc(string(b), 12000))
	if _, err := p.stdin.Write(append(b, '\n')); err != nil {
		fmt.Printf("%s SEND ERR %v\n", p.name, err)
	}
}

func (p *acpProc) notify(method string, params any) {
	m := rpcMsg{"jsonrpc": "2.0", "method": method}
	if params != nil {
		m["params"] = params
	}
	b, _ := json.Marshal(m)
	p.sendLine(b)
}

func (p *acpProc) respond(id any, result rpcMsg) {
	b, _ := json.Marshal(rpcMsg{"jsonrpc": "2.0", "id": id, "result": result})
	p.sendLine(b)
}

func (p *acpProc) kill() {
	if p == nil || p.cmd == nil || p.cmd.Process == nil {
		return
	}
	fmt.Printf("=== killing %s pid %d (taskkill /T /F)\n", p.name, p.pid)
	if runtime.GOOS == "windows" {
		exec.Command("taskkill", "/T", "/F", "/PID", strconv.Itoa(p.pid)).Run()
	} else {
		p.cmd.Process.Kill()
	}
	p.cmd.Wait() // reap; the read goroutine exits on EOF
}

// --- probe state -----------------------------------------------------------

type permRec struct {
	at          time.Time
	sid         string // sessionId in the request params ("" if absent — evidence!)
	tool        string
	options     []string
	updatesHeld map[string]int // per-tag session/update count while we held the reply
	heldFor     time.Duration
}

type sessStats struct {
	sid       string
	tag       string // "A"/"B"
	updates   int
	firstUpd  time.Time
	lastUpd   time.Time
	done      bool
	stop      string // prompt stopReason or error summary
	cancelled bool
	perms     int
}

type multiState struct {
	mu             sync.Mutex
	pending        map[string]chan rpcMsg // request id -> response chan
	sessBySid      map[string]*sessStats
	updatesBySid   map[string]int
	unknownUpdates int
	unknownSamples []string
	perms          []*permRec
	extNotifSid    map[string]int // _cognition.ai/* notification sid histogram

	// cancel coordination
	sidA, sidB string
	onAPerm    func() // fired once when A's first permission request arrives
	cancelSent bool
	cancelAt   time.Time

	// stage results for the verdict block
	bAtCancel      int // B updates received before session/cancel was sent
	bUpdatesAfterC int // B updates received after session/cancel
	thirdTarget    string
	thirdResp      *rpcMsg
	thirdTimeout   bool
	yListA         string // what Y's session/list reports about A
	yListB         string
	yResp          *rpcMsg
	yTimeout       bool
}

func newMultiState() *multiState {
	return &multiState{
		pending:      make(map[string]chan rpcMsg),
		sessBySid:    make(map[string]*sessStats),
		updatesBySid: make(map[string]int),
		extNotifSid:  make(map[string]int),
	}
}

func (s *multiState) track(id string) chan rpcMsg {
	ch := make(chan rpcMsg, 1)
	s.mu.Lock()
	s.pending[id] = ch
	s.mu.Unlock()
	return ch
}

// call sends a request on p, having registered the response channel first so
// a fast reply can't race the track.
func (s *multiState) call(p *acpProc, method string, params any) (string, chan rpcMsg) {
	id := fmt.Sprintf("%s%d", strings.ToLower(p.name), nextID.Add(1))
	ch := s.track(id)
	m := rpcMsg{"jsonrpc": "2.0", "id": id, "method": method}
	if params != nil {
		m["params"] = params
	}
	b, _ := json.Marshal(m)
	p.sendLine(b)
	return id, ch
}

func waitResp(ch chan rpcMsg, timeout time.Duration) (rpcMsg, bool) {
	select {
	case m := <-ch:
		return m, true
	case <-time.After(timeout):
		return nil, false
	}
}

func (s *multiState) addSess(tag, sid string) {
	s.mu.Lock()
	s.sessBySid[sid] = &sessStats{sid: sid, tag: tag}
	if tag == "A" {
		s.sidA = sid
	} else if tag == "B" {
		s.sidB = sid
	}
	s.mu.Unlock()
}

func (s *multiState) sidTag(sid string) string {
	if st, ok := s.sessBySid[sid]; ok {
		return st.tag
	}
	if sid == "" {
		return "NO-SID"
	}
	return sid // foreign but real: third-session replay, or an A/B update that raced addSess
}

func (s *multiState) snapshotUpdates() map[string]int {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make(map[string]int, len(s.updatesBySid))
	for k, v := range s.updatesBySid {
		out[k] = v
	}
	return out
}

func pickAllowOption(opts []any) (any, []string) {
	var descs []string
	var fallback any
	for _, o := range opts {
		om, _ := o.(rpcMsg)
		name, _ := om["name"].(string)
		kind, _ := om["kind"].(string)
		oid := om["optionId"]
		descs = append(descs, fmt.Sprintf("%v(%v %v)", oid, kind, name))
		if fallback == nil {
			fallback = oid
		}
		if strings.Contains(strings.ToLower(kind+" "+name), "allow") {
			return oid, descs
		}
	}
	return fallback, descs
}

// dispatch is the X-process message handler (runs in one goroutine).
func (s *multiState) dispatch(p *acpProc, m rpcMsg) {
	method, _ := m["method"].(string)
	if method == "" { // response
		id := fmt.Sprint(m["id"])
		s.mu.Lock()
		ch := s.pending[id]
		delete(s.pending, id)
		s.mu.Unlock()
		if ch != nil {
			ch <- m
		} else {
			fmt.Printf("%s <<< [stray response] %s\n", p.name, trunc(string(mustJSON(m)), 2000))
		}
		return
	}
	if m["id"] != nil { // agent -> client request
		switch method {
		case "session/request_permission":
			pr, _ := m["params"].(rpcMsg)
			sid, _ := pr["sessionId"].(string)
			tc, _ := pr["toolCall"].(rpcMsg)
			opts, _ := pr["options"].([]any)
			tool, _ := tc["title"].(string)
			if tool == "" || tool == "<nil>" {
				if meta, _ := tc["_meta"].(rpcMsg); meta != nil {
					tool, _ = meta["cognition.ai/editableCommand"].(string)
				}
			}
			if tool == "" {
				tool = fmt.Sprint(tc["toolCallId"])
			}
			rec := &permRec{at: time.Now(), sid: sid, tool: tool}
			s.mu.Lock()
			s.perms = append(s.perms, rec)
			if st := s.sessBySid[sid]; st != nil {
				st.perms++
			}
			var hook func()
			if sid == s.sidA && s.onAPerm != nil {
				hook, s.onAPerm = s.onAPerm, nil
			}
			s.mu.Unlock()
			if hook != nil {
				hook()
			}
			fmt.Printf("%s <<< PERMISSION sid=%q(%s) tool=%q\n", p.name, sid, s.sidTag(sid), rec.tool)
			fmt.Printf("%s <<< [perm-raw] %s\n", p.name, trunc(string(mustJSON(m)), 4000))
			snap := s.snapshotUpdates()
			reqID := m["id"]
			time.AfterFunc(permHold, func() {
				opt, descs := pickAllowOption(opts)
				rec.options = descs
				if opt != nil {
					p.respond(reqID, rpcMsg{"outcome": rpcMsg{"outcome": "selected", "optionId": opt}})
				} else {
					p.respond(reqID, rpcMsg{"outcome": rpcMsg{"outcome": "cancelled"}})
				}
				rec.heldFor = time.Since(rec.at)
				rec.updatesHeld = tagCounts(s, diffCounts(snap, s.snapshotUpdates()))
				fmt.Printf("%s <<< PERMISSION sid=%q(%s) replied allow after %s; updates-while-held=%v\n",
					p.name, sid, s.sidTag(sid), rec.heldFor.Round(time.Millisecond), rec.updatesHeld)
			})
		default:
			fmt.Printf("%s <<< [agent-req] %s: %s\n", p.name, method, trunc(string(mustJSON(m)), 2000))
			p.respond(m["id"], nil)
		}
		return
	}
	// notification
	switch {
	case method == "session/update":
		pr, _ := m["params"].(rpcMsg)
		sid, _ := pr["sessionId"].(string)
		u, _ := pr["update"].(rpcMsg)
		utype, _ := u["sessionUpdate"].(string)
		s.mu.Lock()
		s.updatesBySid[sid]++
		st := s.sessBySid[sid]
		if st != nil {
			st.updates++
			st.lastUpd = time.Now()
			if st.firstUpd.IsZero() {
				st.firstUpd = st.lastUpd
			}
		} else {
			s.unknownUpdates++
			if len(s.unknownSamples) < 5 {
				s.unknownSamples = append(s.unknownSamples, trunc(string(mustJSON(m)), 800))
			}
		}
		s.mu.Unlock()
		switch utype {
		case "agent_message_chunk", "agent_thought_chunk":
			c, _ := u["content"].(rpcMsg)
			fmt.Printf("%s <<< [upd %s %s] %v", p.name, s.sidTag(sid), utype, trunc(fmt.Sprint(c["text"]), 200))
		default:
			fmt.Printf("%s <<< [upd %s %s] %s\n", p.name, s.sidTag(sid), utype, trunc(string(mustJSON(u)), 300))
		}
	case strings.HasPrefix(method, "_cognition.ai/"):
		pr, _ := m["params"].(rpcMsg)
		sid, _ := pr["sessionId"].(string)
		s.mu.Lock()
		s.extNotifSid[sid]++
		s.mu.Unlock()
	default:
		fmt.Printf("%s <<< [notif] %s %s\n", p.name, method, trunc(string(mustJSON(m["params"])), 400))
	}
}

func diffCounts(before, after map[string]int) map[string]int {
	out := map[string]int{}
	for k, v := range after {
		if d := v - before[k]; d > 0 {
			out[k] = d
		}
	}
	return out
}

func tagCounts(s *multiState, m map[string]int) map[string]int {
	out := map[string]int{}
	for sid, n := range m {
		out[s.sidTag(sid)] = n
	}
	return out
}

// drainY is the handler for process Y: routes responses, auto-approves
// permissions immediately, logs non-extension notifications compactly.
func (s *multiState) drainY(p *acpProc, m rpcMsg) {
	method, _ := m["method"].(string)
	if method == "" {
		id := fmt.Sprint(m["id"])
		s.mu.Lock()
		ch := s.pending[id]
		delete(s.pending, id)
		s.mu.Unlock()
		if ch != nil {
			ch <- m
		} else {
			fmt.Printf("%s <<< [stray response] %s\n", p.name, trunc(string(mustJSON(m)), 2000))
		}
		return
	}
	if m["id"] != nil {
		if method == "session/request_permission" {
			pr, _ := m["params"].(rpcMsg)
			opts, _ := pr["options"].([]any)
			opt, _ := pickAllowOption(opts)
			fmt.Printf("%s <<< PERMISSION (Y) %s -> auto-allow\n", p.name, trunc(string(mustJSON(pr)), 600))
			if opt != nil {
				p.respond(m["id"], rpcMsg{"outcome": rpcMsg{"outcome": "selected", "optionId": opt}})
			} else {
				p.respond(m["id"], rpcMsg{"outcome": rpcMsg{"outcome": "cancelled"}})
			}
		} else {
			fmt.Printf("%s <<< [agent-req Y] %s\n", p.name, method)
			p.respond(m["id"], nil)
		}
		return
	}
	if !strings.HasPrefix(method, "_cognition.ai/") {
		fmt.Printf("%s <<< [notif Y] %s %s\n", p.name, method, trunc(string(mustJSON(m["params"])), 300))
	}
}

func respErr(r rpcMsg) string {
	if r == nil {
		return ""
	}
	if e, _ := r["error"].(rpcMsg); e != nil {
		return fmt.Sprintf("ERR %v: %v", e["code"], trunc(fmt.Sprint(e["message"]), 300))
	}
	return ""
}

// --- the scenario ----------------------------------------------------------

func runMultiSession(cwd string) {
	started := time.Now()
	fmt.Printf("=== MULTI-SESSION PROBE start %s cwd=%s ===\n",
		started.Format(time.RFC3339), cwd)

	state := newMultiState()

	X, err := spawnACP(cwd, "X")
	if err != nil {
		fmt.Println("SPAWN X FAILED:", err)
		return
	}
	defer X.kill()
	go func() {
		for m := range X.inbox {
			state.dispatch(X, m)
		}
		fmt.Println("=== X inbox closed (process exited)")
	}()

	initParams := func() rpcMsg {
		return rpcMsg{
			"protocolVersion":    1,
			"clientCapabilities": rpcMsg{},
			"clientInfo":         rpcMsg{"name": "acptest", "version": "0"},
		}
	}
	auth := func(p *acpProc) bool {
		key := devinAPIKey()
		if key == "" {
			fmt.Printf("%s !!! no api key (DEVIN_API_KEY/credentials.toml); auth may fail\n", p.name)
		}
		_, ch := state.call(p, "authenticate", rpcMsg{
			"methodId": "devin-browser",
			"_meta":    rpcMsg{"api_key": key},
		})
		r, ok := waitResp(ch, authTimeout)
		if !ok {
			fmt.Printf("%s AUTH TIMEOUT\n", p.name)
			return false
		}
		if e := respErr(r); e != "" {
			fmt.Printf("%s AUTH %s\n", p.name, e)
			return false
		}
		fmt.Printf("%s AUTH OK\n", p.name)
		return true
	}
	newSess := func(p *acpProc, dir string) (string, bool) {
		_, ch := state.call(p, "session/new", rpcMsg{"cwd": dir, "mcpServers": []any{}})
		r, ok := waitResp(ch, newTimeout)
		if !ok {
			fmt.Printf("%s session/new TIMEOUT\n", p.name)
			return "", false
		}
		if e := respErr(r); e != "" {
			fmt.Printf("%s session/new %s\n", p.name, e)
			return "", false
		}
		res, _ := r["result"].(rpcMsg)
		sid, _ := res["sessionId"].(string)
		fmt.Printf("%s session/new OK -> %s\n", p.name, sid)
		return sid, true
	}

	// 1. initialize on X
	_, chInit := state.call(X, "initialize", initParams())
	r, ok := waitResp(chInit, initTimeout)
	if !ok {
		fmt.Println("X INIT TIMEOUT")
		return
	}
	fmt.Printf("X INIT RESULT: %s\n", trunc(string(mustJSON(r["result"])), 3000))
	if e := respErr(r); e != "" {
		fmt.Println("X INIT", e)
		return
	}
	if !auth(X) {
		fmt.Println("=== aborting: X not authenticated ===")
		return
	}

	// Per-session workspaces.
	dirA := filepath.Join(os.TempDir(), fmt.Sprintf("acptest-multi-a-%d", os.Getpid()))
	dirB := filepath.Join(os.TempDir(), fmt.Sprintf("acptest-multi-b-%d", os.Getpid()))
	os.MkdirAll(dirA, 0o755)
	os.MkdirAll(dirB, 0o755)
	defer os.RemoveAll(dirA)
	defer os.RemoveAll(dirB)

	sidA, ok1 := newSess(X, dirA)
	if !ok1 {
		fmt.Println("=== aborting: session A not created ===")
		return
	}
	state.addSess("A", sidA)
	sidB, ok2 := newSess(X, dirB)
	if !ok2 {
		fmt.Println("=== aborting: session B not created (A exists:", sidA, ") ===")
		return
	}
	state.addSess("B", sidB)
	fmt.Printf("=== sessions up: A=%s B=%s ===\n", sidA, sidB)

	prompt := func(file, prefix string) string {
		return fmt.Sprintf("In the current directory: "+
			"1) create a file named %s containing exactly 40 lines, where line N is %q. "+
			"2) run this exact terminal command: sleep 30 && wc -l %s "+
			"3) append one more line \"done\" to the file. "+
			"Then report the count the command printed. Do not ask me anything; just do it.",
			file, prefix+"-N", file)
	}
	_, chA := state.call(X, "session/prompt", rpcMsg{
		"sessionId": sidA,
		"prompt":    []any{rpcMsg{"type": "text", "text": prompt("probe_a.txt", "a-line")}},
	})
	_, chB := state.call(X, "session/prompt", rpcMsg{
		"sessionId": sidB,
		"prompt":    []any{rpcMsg{"type": "text", "text": prompt("probe_b.txt", "b-line")}},
	})
	fmt.Printf("=== prompts sent; permHold=%s cancel=3s-after-A-first-permission (fallback %s) ===\n",
		permHold, cancelAfter)

	// Concurrent window: wait for both prompt results; cancel A mid-flight;
	// while B still runs, session/load a third (pre-existing) session.
	// The cancel fires 3s after A's first permission request (cancel-during-
	// pending-permission is the interesting case), or at cancelAfter.
	sendCancel := func() {
		state.mu.Lock()
		if state.cancelSent {
			state.mu.Unlock()
			return
		}
		state.cancelSent = true
		state.cancelAt = time.Now()
		if st := state.sessBySid[sidA]; st != nil {
			st.cancelled = true
		}
		state.bAtCancel = state.updatesBySid[sidB]
		at := state.cancelAt
		state.mu.Unlock()
		fmt.Printf("=== sending session/cancel (notification) for A=%s at %s ===\n",
			sidA, at.Format("15:04:05.000"))
		X.notify("session/cancel", rpcMsg{"sessionId": sidA})
	}
	state.mu.Lock()
	state.onAPerm = func() { time.AfterFunc(3*time.Second, sendCancel) }
	state.mu.Unlock()
	time.AfterFunc(cancelAfter, sendCancel)

	var resA, resB rpcMsg
	var gotA, gotB, loadedThird bool
	promptEnd := time.After(promptDeadline)
	tick := time.NewTicker(500 * time.Millisecond)
	defer tick.Stop()

	for !(gotA && gotB) {
		select {
		case m := <-chA:
			gotA, resA = true, m
			markDone(state, sidA, m, "A")
		case m := <-chB:
			gotB, resB = true, m
			markDone(state, sidB, m, "B")
		case <-tick.C:
			state.mu.Lock()
			cs := state.cancelSent
			state.mu.Unlock()
			if cs && !loadedThird && gotA && !gotB {
				loadedThird = true
				go loadThirdSession(X, state, sidA, sidB)
			}
		case <-promptEnd:
			fmt.Println("=== PROMPT STAGE DEADLINE (4m) hit ===")
			goto promptDone
		}
	}
promptDone:
	state.mu.Lock()
	state.bUpdatesAfterC = state.updatesBySid[sidB] - state.bAtCancel
	state.mu.Unlock()
	fmt.Printf("=== prompt stage done: A done=%v B done=%v ===\n", gotA, gotB)
	if resA != nil {
		fmt.Printf("X PROMPT A RESULT: %s\n", trunc(string(mustJSON(first(resA["result"], resA["error"]))), 2000))
	}
	if resB != nil {
		fmt.Printf("X PROMPT B RESULT: %s\n", trunc(string(mustJSON(first(resB["result"], resB["error"]))), 2000))
	}
	if !loadedThird {
		loadedThird = true
		fmt.Println("=== B settled before the load-window; running third-session load now ===")
		loadThirdSession(X, state, sidA, sidB)
	}

	// Process Y: try to session/load A while X still holds it.
	fmt.Println("=== spawning process Y for cross-process lock test ===")
	Y, err := spawnACP(cwd, "Y")
	if err != nil {
		fmt.Println("SPAWN Y FAILED:", err)
	} else {
		go func() {
			for m := range Y.inbox {
				state.drainY(Y, m)
			}
			fmt.Println("=== Y inbox closed")
		}()
		_, chYI := state.call(Y, "initialize", initParams())
		ry, ok := waitResp(chYI, initTimeout)
		if !ok || respErr(ry) != "" {
			fmt.Println("Y INIT FAIL:", trunc(string(mustJSON(ry)), 500))
		} else if !auth(Y) {
			fmt.Println("Y AUTH FAIL")
		} else {
			// What does session/list in Y say about A's lock state?
			_, chL := state.call(Y, "session/list", rpcMsg{"sessionId": "y-list"})
			lr, _ := waitResp(chL, 30*time.Second)
			if res, _ := lr["result"].(rpcMsg); res != nil {
				for _, sv := range res["sessions"].([]any) {
					if sm, ok := sv.(rpcMsg); ok {
						sid, _ := sm["sessionId"].(string)
						line := trunc(string(mustJSON(sm)), 500)
						fmt.Printf("Y LIST sees %s(%s): %s\n", sid, state.sidTag(sid), line)
						state.mu.Lock()
						if sid == sidA {
							state.yListA = line
						} else if sid == sidB {
							state.yListB = line
						}
						state.mu.Unlock()
					}
				}
			} else {
				fmt.Printf("Y LIST resp: %s\n", trunc(string(mustJSON(lr)), 500))
			}
			_, chL2 := state.call(Y, "session/load", rpcMsg{
				"sessionId":  sidA,
				"cwd":        dirA,
				"mcpServers": []any{},
			})
			lr2, ok := waitResp(chL2, loadTimeout)
			state.mu.Lock()
			if !ok {
				state.yTimeout = true
				fmt.Println("Y session/load A: NO RESPONSE within", loadTimeout)
			} else {
				state.yResp = &lr2
				fmt.Printf("Y session/load A RESP: %s\n", trunc(string(mustJSON(lr2)), 2000))
			}
			state.mu.Unlock()
		}
		fmt.Println("=== killing Y ===")
		Y.kill()
	}

	// Give B a last chance to finish if it's still streaming.
	if !gotB {
		fmt.Println("=== waiting up to 60s more for B ===")
		select {
		case m := <-chB:
			gotB, resB = true, m
			markDone(state, sidB, m, "B")
			fmt.Printf("X PROMPT B RESULT: %s\n", trunc(string(mustJSON(first(resB["result"], resB["error"]))), 2000))
		case <-time.After(60 * time.Second):
			fmt.Println("=== B still running at end; stopping probe ===")
		}
	}

	printVerdicts(state, sidA, sidB)
	fmt.Printf("=== MULTI-SESSION PROBE end, elapsed %s ===\n", time.Since(started).Round(time.Second))
}

func first(a, b any) any {
	if a != nil {
		return a
	}
	return b
}

func markDone(st *multiState, sid string, resp rpcMsg, tag string) {
	st.mu.Lock()
	defer st.mu.Unlock()
	if s := st.sessBySid[sid]; s != nil {
		s.done = true
		if res, _ := resp["result"].(rpcMsg); res != nil {
			s.stop, _ = res["stopReason"].(string)
		}
		if e := respErr(resp); e != "" {
			s.stop = "error: " + e
		}
	}
	fmt.Printf("=== %s prompt settled (sid=%s) ===\n", tag, sid)
}

// loadThirdSession lists sessions on X and session/load's an existing
// unlocked session that is neither A nor B — ideally while B is streaming.
func loadThirdSession(X *acpProc, state *multiState, sidA, sidB string) {
	_, chL := state.call(X, "session/list", rpcMsg{"sessionId": "x-list"})
	lr, ok := waitResp(chL, 30*time.Second)
	if !ok {
		fmt.Println("X session/list (third-session test) TIMEOUT")
		return
	}
	target, tcwd := "", ""
	if res, _ := lr["result"].(rpcMsg); res != nil {
		if sessions, _ := res["sessions"].([]any); sessions != nil {
			fmt.Printf("X LIST: %d sessions; lock states:\n", len(sessions))
			for _, sv := range sessions {
				sm, _ := sv.(rpcMsg)
				sid, _ := sm["sessionId"].(string)
				meta, _ := sm["_meta"].(rpcMsg)
				locked, _ := meta["cognition.ai/isLocked"].(bool)
				c, _ := sm["cwd"].(string)
				mark := ""
				if sid == sidA {
					mark = " [A]"
				} else if sid == sidB {
					mark = " [B]"
				}
				fmt.Printf("   - %s locked=%v cwd=%s%s\n", sid, locked, c, mark)
				if target == "" && !locked && sid != sidA && sid != sidB {
					target, tcwd = sid, c
				}
			}
		}
	} else {
		fmt.Printf("X session/list resp: %s\n", trunc(string(mustJSON(lr)), 500))
	}
	if target == "" {
		fmt.Println("X third-session load: no unlocked third session; skipping")
		state.mu.Lock()
		state.thirdTarget = "(none found)"
		state.mu.Unlock()
		return
	}
	if tcwd == "" {
		tcwd, _ = os.Getwd()
	}
	fmt.Printf("=== session/load third session %s while B running ===\n", target)
	_, chL2 := state.call(X, "session/load", rpcMsg{
		"sessionId":  target,
		"cwd":        tcwd,
		"mcpServers": []any{},
	})
	lr2, ok := waitResp(chL2, loadTimeout)
	state.mu.Lock()
	state.thirdTarget = target
	if !ok {
		state.thirdTimeout = true
	} else {
		state.thirdResp = &lr2
	}
	state.mu.Unlock()
	if !ok {
		fmt.Println("X session/load third: NO RESPONSE within", loadTimeout)
		return
	}
	fmt.Printf("X session/load third RESP: %s\n", trunc(string(mustJSON(lr2)), 1500))
}

func printVerdicts(st *multiState, sidA, sidB string) {
	st.mu.Lock()
	defer st.mu.Unlock()
	fmt.Println("\n================= MULTI-SESSION VERDICTS =================")
	sA, sB := st.sessBySid[sidA], st.sessBySid[sidB]
	updA, updB := 0, 0
	if sA != nil {
		updA = sA.updates
	}
	if sB != nil {
		updB = sB.updates
	}

	// [1] sessionId on every session/update. A violation = a session/update
	// with NO sessionId. Updates tagged with a foreign-but-real sessionId
	// (the loaded third session's replay; A/B frames that arrived during
	// session/new before addSess) are correct routing, not violations.
	total := 0
	foreign := map[string]int{}
	for sid, n := range st.updatesBySid {
		total += n
		if sid != "" && sid != sidA && sid != sidB {
			foreign[sid] = n
		}
	}
	missingSid := st.updatesBySid[""]
	v1 := "PASS"
	if missingSid > 0 {
		v1 = "FAIL"
	}
	fmt.Printf("[1] session/update sessionId: %s — total=%d A=%d B=%d missing-sid=%d foreign-sessions=%v\n",
		v1, total, updA, updB, missingSid, foreign)
	for _, smp := range st.unknownSamples {
		fmt.Println("    foreign-sid sample:", smp)
	}
	fmt.Printf("    _cognition.ai/* sid histogram: %v\n", st.extNotifSid)

	// [2] permission requests: sessionId present? cross-session blocking?
	fmt.Printf("[2] session/request_permission: %d requests\n", len(st.perms))
	missing := 0
	for i, rec := range st.perms {
		sidMark := rec.sid
		if sidMark == "" {
			missing++
			sidMark = "(ABSENT)"
		}
		fmt.Printf("    #%d at %s sid=%s tool=%q held=%s updates-while-held=%v opts=%v\n",
			i+1, rec.at.Format("15:04:05.000"), sidMark, rec.tool,
			rec.heldFor.Round(time.Millisecond), rec.updatesHeld, rec.options)
	}
	switch {
	case len(st.perms) == 0:
		fmt.Println("    => no permission requests observed (cannot judge sid/blocking)")
	case missing > 0:
		fmt.Printf("    => %d requests LACKED sessionId\n", missing)
	default:
		fmt.Println("    => all requests carried sessionId")
	}

	// [3] cancel A while B runs
	v3 := "SKIP (no cancel sent)"
	if st.cancelSent {
		aStop := "?"
		if sA != nil {
			aStop = fmt.Sprintf("done=%v stop=%q", sA.done, sA.stop)
		}
		bStop := "?"
		if sB != nil {
			bStop = fmt.Sprintf("done=%v stop=%q", sB.done, sB.stop)
		}
		v3 = fmt.Sprintf("cancel sent at %s; A: %s; B after cancel: +%d updates, %s",
			st.cancelAt.Format("15:04:05.000"), aStop, st.bUpdatesAfterC, bStop)
	}
	fmt.Printf("[3] session/cancel A => %s\n", v3)

	// [4] third-session load while B running
	switch {
	case st.thirdResp != nil:
		fmt.Printf("[4] session/load %s (third session) => %s\n",
			st.thirdTarget, trunc(string(mustJSON(first((*st.thirdResp)["result"], (*st.thirdResp)["error"]))), 500))
	case st.thirdTimeout:
		fmt.Printf("[4] session/load %s => TIMEOUT after %s\n", st.thirdTarget, loadTimeout)
	default:
		fmt.Printf("[4] third-session load not run: %s\n", st.thirdTarget)
	}

	// [5] process Y loading A while X holds it
	switch {
	case st.yResp != nil:
		if e := respErr(*st.yResp); e != "" {
			fmt.Printf("[5] Y session/load A => REJECTED: %s\n", e)
		} else {
			fmt.Printf("[5] Y session/load A => ALLOWED (unexpected!): %s\n",
				trunc(string(mustJSON((*st.yResp)["result"])), 400))
		}
	case st.yTimeout:
		fmt.Println("[5] Y session/load A => TIMEOUT (hung — no verdict)")
	default:
		fmt.Println("[5] Y test did not run")
	}
	if st.yListA != "" {
		fmt.Printf("    Y session/list saw A as: %s\n", st.yListA)
	}
	fmt.Println("=========================================================")
}
