// Temporary end-to-end ACP probe: drives the same message flow the Android
// app will use — initialize -> session/new -> authenticate -> prompt —
// through the bridge WebSocket, printing everything it sees.
//
// Usage: go run ./acptest "ws://127.0.0.1:8790/acp?token=SECRET" "C:\path\cwd" "prompt text"
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
)

var nextID = atomic.Int64{}

// probeRaw makes handleNotif dump every inbound message as raw JSON (probe mode).
var probeRaw bool

// Alias (not named type) so `x.(rpcMsg)` assertions match maps decoded by
// encoding/json, which produces map[string]interface{}.
type rpcMsg = map[string]any

func send(conn *websocket.Conn, ctx context.Context, m rpcMsg) error {
	b, _ := json.Marshal(m)
	fmt.Printf(">>> %s\n", string(b))
	return conn.Write(ctx, websocket.MessageText, b)
}

func request(conn *websocket.Conn, ctx context.Context, method string, params rpcMsg) int64 {
	id := nextID.Add(1)
	m := rpcMsg{"jsonrpc": "2.0", "id": id, "method": method}
	if params != nil {
		m["params"] = params
	}
	if err := send(conn, ctx, m); err != nil {
		fmt.Println("SEND ERR", err)
		os.Exit(1)
	}
	return id
}

// requestP is request() with arbitrary (or absent) params for probe calls.
func requestP(conn *websocket.Conn, ctx context.Context, method string, params any) int64 {
	id := nextID.Add(1)
	m := rpcMsg{"jsonrpc": "2.0", "id": id, "method": method}
	if params != nil {
		m["params"] = params
	}
	if err := send(conn, ctx, m); err != nil {
		fmt.Println("SEND ERR", err)
		os.Exit(1)
	}
	return id
}

func reply(conn *websocket.Conn, ctx context.Context, id any, result rpcMsg) {
	send(conn, ctx, rpcMsg{"jsonrpc": "2.0", "id": id, "result": result})
}

func isAuthErr(e rpcMsg) bool {
	if e == nil {
		return false
	}
	code, _ := e["code"].(float64)
	msg, _ := e["message"].(string)
	lm := strings.ToLower(msg)
	return code == -32000 || strings.Contains(lm, "auth") || strings.Contains(lm, "login")
}

func main() {
	wsURL, cwd, prompt := os.Args[1], os.Args[2], os.Args[3]

	ctx := context.Background()
	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{HTTPClient: http.DefaultClient})
	if err != nil {
		fmt.Println("DIAL ERR", err)
		os.Exit(1)
	}
	defer conn.Close(websocket.StatusNormalClosure, "done")

	read := make(chan rpcMsg, 256)
	go func() {
		for {
			_, data, err := conn.Read(ctx)
			if err != nil {
				close(read)
				return
			}
			var m rpcMsg
			if json.Unmarshal(data, &m) == nil {
				read <- m
			}
		}
	}()

	waitResp := func(id int64, timeout time.Duration) rpcMsg {
		deadline := time.After(timeout)
		for {
			select {
			case m, ok := <-read:
				if !ok {
					fmt.Println("conn closed")
					os.Exit(1)
				}
				if m["id"] != nil && !hasMethod(m) {
					if fmt.Sprint(m["id"]) == fmt.Sprint(id) {
						return m
					}
					continue
				}
				handleNotif(conn, ctx, m)
			case <-deadline:
				fmt.Println("TIMEOUT waiting for id", id)
				os.Exit(1)
			}
		}
	}

	// probe:<label> mode — capability/method prober. Drives initialize with
	// clientCapabilities._meta flags (PROBE_CAPS), then fires PROBE_METHODS at
	// PROBE_SESSION, then collects notifications. Never runs authenticate:
	// auth_required is retried once, then recorded as skipped.
	if strings.HasPrefix(prompt, "probe:") {
		runProbe(conn, ctx, read, cwd, strings.TrimPrefix(prompt, "probe:"))
		return
	}

	// 1. initialize
	r := waitResp(request(conn, ctx, "initialize", rpcMsg{
		"protocolVersion":    1,
		"clientCapabilities": rpcMsg{},
		"clientInfo":         rpcMsg{"name": "acptest", "version": "0"},
	}), 30*time.Second)
	fmt.Println("INIT OK")
	authed := false

	// load:<id> mode — list sessions, then session/load and dump the replay.
	if strings.HasPrefix(prompt, "load:") {
		sid := strings.TrimPrefix(prompt, "load:")
		r = waitResp(request(conn, ctx, "session/list", rpcMsg{}), 60*time.Second)
		if res, _ := r["result"].(rpcMsg); res != nil {
			fmt.Println("LIST:", string(mustJSON(res["sessions"]))[:2000])
		}
		load := func() rpcMsg {
			return waitResp(request(conn, ctx, "session/load", rpcMsg{
				"sessionId":  sid,
				"cwd":        cwd,
				"mcpServers": []any{},
			}), 120*time.Second)
		}
		r = load()
		if e, _ := r["error"].(rpcMsg); isAuthErr(e) {
			fmt.Println("AUTH REQUIRED -> authenticate")
			waitResp(request(conn, ctx, "authenticate", rpcMsg{"methodId": "devin-browser"}), 15*time.Minute)
			fmt.Println("AUTH OK")
			r = load()
		}
		if e, _ := r["error"].(rpcMsg); e != nil {
			fmt.Printf("LOAD ERR %v: %v\n", e["code"], e["message"])
			os.Exit(1)
		}
		fmt.Println("LOAD OK:", string(mustJSON(r["result"])))
		// Optionally probe session/set_mode|set_model|set_config_option once.
		if m := os.Getenv("TRY_METHOD"); m != "" {
			params := rpcMsg{"sessionId": sid}
			switch m {
			case "session/set_mode":
				params["modeId"] = os.Getenv("TRY_VALUE")
			case "session/set_model":
				params["modelId"] = os.Getenv("TRY_VALUE")
			case "session/set_config_option":
				params["configId"] = os.Getenv("TRY_CONFIG")
				params["value"] = os.Getenv("TRY_VALUE")
			}
			rr := waitResp(request(conn, ctx, m, params), 30*time.Second)
			if e, _ := rr["error"].(rpcMsg); e != nil {
				fmt.Printf("SET ERR %v: %v\n", e["code"], e["message"])
			} else {
				fmt.Println("SET OK:", string(mustJSON(rr["result"])))
			}
		}
		deadline := time.After(20 * time.Second)
		for {
			select {
			case m, ok := <-read:
				if !ok {
					fmt.Println("conn closed")
					return
				}
				handleNotif(conn, ctx, m)
			case <-deadline:
				fmt.Println("--- replay window over ---")
				return
			}
		}
	}

	// 2. session/new
	newSession := func() rpcMsg {
		return waitResp(request(conn, ctx, "session/new", rpcMsg{
			"cwd":        cwd,
			"mcpServers": []any{},
		}), 60*time.Second)
	}

	r = newSession()
	if e, _ := r["error"].(rpcMsg); isAuthErr(e) {
		fmt.Println("AUTH REQUIRED -> authenticate (devin-browser)")
		// 3. authenticate — blocks until browser login completes (15 min)
		r = waitResp(request(conn, ctx, "authenticate", rpcMsg{"methodId": "devin-browser"}), 15*time.Minute)
		if e, _ := r["error"].(rpcMsg); e != nil {
			fmt.Println("AUTH FAILED:", e["message"])
			os.Exit(1)
		}
		authed = true
		fmt.Println("AUTH OK")
		r = newSession()
	}
	if e, _ := r["error"].(rpcMsg); e != nil {
		fmt.Printf("SESSION/NEW ERR %v: %v\n", e["code"], e["message"])
		os.Exit(1)
	}
	res, _ := r["result"].(rpcMsg)
	sid, _ := res["sessionId"].(string)
	fmt.Println("SESSION:", sid, "(authed this run:", authed, ")")

	// 4. prompt — stream session/update notifications until the result arrives
	pid := request(conn, ctx, "session/prompt", rpcMsg{
		"sessionId": sid,
		"prompt":    []any{rpcMsg{"type": "text", "text": prompt}},
	})
	deadline := time.After(10 * time.Minute)
	for {
		select {
		case m, ok := <-read:
			if !ok {
				fmt.Println("conn closed")
				os.Exit(1)
			}
			if m["id"] != nil && !hasMethod(m) {
				if fmt.Sprint(m["id"]) == fmt.Sprint(pid) {
					fmt.Println("PROMPT RESULT:", string(mustJSON(m["result"])))
					if e, _ := m["error"].(rpcMsg); e != nil {
						fmt.Println("PROMPT ERR:", e["message"])
						os.Exit(1)
					}
					return
				}
				continue
			}
			handleNotif(conn, ctx, m)
		case <-deadline:
			fmt.Println("prompt timeout")
			os.Exit(1)
		}
	}
}

func hasMethod(m rpcMsg) bool { _, ok := m["method"]; return ok }

func trunc(s string, n int) string {
	if len(s) > n {
		return s[:n] + "…"
	}
	return s
}

func mustJSON(v any) []byte {
	b, _ := json.Marshal(v)
	return b
}

// allProbeCaps are the aither_acp-style capability flags probed via
// clientCapabilities._meta. PROBE_CAPS picks a subset.
var allProbeCaps = []string{
	"cognition.ai/revert",
	"cognition.ai/multiRootWorkspace",
	"cognition.ai/subagentSupport",
	"cognition.ai/sessionRename",
	"cognition.ai/sessionShare",
}

// probeCaps parses PROBE_CAPS: unset/"all" -> all flags; "none" -> omit _meta;
// otherwise a comma-separated list (bare suffixes get the cognition.ai/ prefix).
func probeCaps() (rpcMsg, bool) {
	spec := strings.TrimSpace(os.Getenv("PROBE_CAPS"))
	if strings.EqualFold(spec, "none") {
		return nil, false
	}
	var keys []string
	if spec == "" || strings.EqualFold(spec, "all") {
		keys = allProbeCaps
	} else {
		for _, t := range strings.Split(spec, ",") {
			t = strings.TrimSpace(t)
			if t == "" {
				continue
			}
			if !strings.Contains(t, "/") {
				t = "cognition.ai/" + t
			}
			keys = append(keys, t)
		}
	}
	meta := rpcMsg{}
	for _, k := range keys {
		meta[k] = true
	}
	return meta, true
}

type probeCall struct {
	method    string
	params    any
	hasParams bool
}

// splitTop splits on commas that are not inside {}, [] or "..." so JSON params
// can appear inside the comma-separated PROBE_METHODS list.
func splitTop(s string) []string {
	var out []string
	depth, start := 0, 0
	inStr, esc := false, false
	for i := 0; i < len(s); i++ {
		c := s[i]
		if esc {
			esc = false
			continue
		}
		if inStr {
			if c == '\\' {
				esc = true
			} else if c == '"' {
				inStr = false
			}
			continue
		}
		switch c {
		case '"':
			inStr = true
		case '{', '[':
			depth++
		case '}', ']':
			depth--
		case ',':
			if depth == 0 {
				out = append(out, s[start:i])
				start = i + 1
			}
		}
	}
	return append(out, s[start:])
}

// parseProbeMethods parses PROBE_METHODS: a comma-separated list where each
// entry is either `method` (default params {"sessionId": sid}) or
// `method|{json}` for explicit params. `{sid}` and `{cwd}` placeholders are
// substituted in both method names and params JSON.
func parseProbeMethods(spec, sid, cwd string) []probeCall {
	var out []probeCall
	for _, tok := range splitTop(spec) {
		tok = strings.TrimSpace(tok)
		if tok == "" {
			continue
		}
		var c probeCall
		if i := strings.Index(tok, "|"); i >= 0 {
			c.method = strings.TrimSpace(tok[:i])
			c.method = strings.ReplaceAll(c.method, "{sid}", sid)
			c.method = strings.ReplaceAll(c.method, "{cwd}", cwd)
			raw := strings.TrimSpace(tok[i+1:])
			// Substitute placeholders as JSON-escaped text so Windows paths
			// like E:\a\b survive inside quoted JSON strings.
			esc := func(v string) string {
				q, _ := json.Marshal(v)
				return strings.Trim(string(q), `"`)
			}
			raw = strings.ReplaceAll(raw, "{sid}", esc(sid))
			raw = strings.ReplaceAll(raw, "{cwd}", esc(cwd))
			if raw == "" {
				c.hasParams = false // `method|` -> no params key at all
			} else if json.Unmarshal([]byte(raw), &c.params) == nil {
				c.hasParams = true
			} else {
				fmt.Printf("!! bad params JSON in %q -> default sessionId params\n", tok)
				c.params, c.hasParams = rpcMsg{"sessionId": sid}, true
			}
		} else {
			c.method = tok
			c.params, c.hasParams = rpcMsg{"sessionId": sid}, true
		}
		out = append(out, c)
	}
	return out
}

func envOr(name, def string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return def
}

func durOr(name string, def time.Duration) time.Duration {
	if v, err := time.ParseDuration(os.Getenv(name)); err == nil && v > 0 {
		return v
	}
	return def
}

func classify(r rpcMsg) string {
	if e, _ := r["error"].(rpcMsg); e != nil {
		return fmt.Sprintf("ERR %v: %v", e["code"], trunc(fmt.Sprint(e["message"]), 120))
	}
	return "OK"
}

// runProbe implements `probe:` mode. Flow: initialize (with PROBE_CAPS flags in
// clientCapabilities._meta) -> print full init result -> fire each
// PROBE_METHODS entry at PROBE_SESSION (default "crawling-side") -> collect
// notifications for PROBE_COLLECT (default 30s). auth_required is retried once
// then marked skipped — authenticate is never invoked here.
func runProbe(conn *websocket.Conn, ctx context.Context, read chan rpcMsg, cwd, label string) {
	probeRaw = true
	sid := envOr("PROBE_SESSION", "crawling-side")
	collect := durOr("PROBE_COLLECT", 30*time.Second)
	mtimeout := durOr("PROBE_TIMEOUT", 20*time.Second)
	meta, hasMeta := probeCaps()

	cc := rpcMsg{}
	if hasMeta {
		cc["_meta"] = meta
	}
	fmt.Printf("=== PROBE %q sid=%s collect=%s meta=%s ===\n",
		label, sid, collect, string(mustJSON(meta)))

	// Non-fatal variant of waitResp: returns ok=false on timeout/close instead
	// of exiting, so one bad method can't kill the whole probe run.
	await := func(id int64, timeout time.Duration) (rpcMsg, bool) {
		deadline := time.After(timeout)
		for {
			select {
			case m, ok := <-read:
				if !ok {
					fmt.Println("conn closed")
					return nil, false
				}
				if m["id"] != nil && !hasMethod(m) {
					if fmt.Sprint(m["id"]) == fmt.Sprint(id) {
						return m, true
					}
					continue
				}
				handleNotif(conn, ctx, m)
			case <-deadline:
				return nil, false
			}
		}
	}

	// 1. initialize — never needs auth; the full result is the point.
	r, ok := await(requestP(conn, ctx, "initialize", rpcMsg{
		"protocolVersion":    1,
		"clientCapabilities": cc,
		"clientInfo":         rpcMsg{"name": "acptest", "version": "0"},
	}), 30*time.Second)
	if !ok {
		fmt.Println("INIT TIMEOUT/CLOSED")
		return
	}
	fmt.Printf("INIT RESULT: %s\n", string(mustJSON(r["result"])))
	if e, _ := r["error"].(rpcMsg); e != nil {
		fmt.Printf("INIT ERR %v: %v\n", e["code"], e["message"])
		return
	}

	// 2. method probes.
	var summary []string
	for _, c := range parseProbeMethods(os.Getenv("PROBE_METHODS"), sid, cwd) {
		call := func() (rpcMsg, bool) {
			var p any
			if c.hasParams {
				p = c.params
			}
			return await(requestP(conn, ctx, c.method, p), mtimeout)
		}
		fmt.Printf("--- probe %s ---\n", c.method)
		resp, ok := call()
		status := ""
		switch {
		case !ok:
			status = "TIMEOUT/CLOSED"
			fmt.Printf("RESP[%s] <no response within %s>\n", c.method, mtimeout)
		default:
			fmt.Printf("RESP[%s]: %s\n", c.method, string(mustJSON(resp)))
			if e, _ := resp["error"].(rpcMsg); isAuthErr(e) {
				fmt.Printf("RESP[%s] looks auth-required; retrying once...\n", c.method)
				time.Sleep(time.Second)
				if resp2, ok2 := call(); ok2 {
					fmt.Printf("RESP[%s retry]: %s\n", c.method, string(mustJSON(resp2)))
					if e2, _ := resp2["error"].(rpcMsg); isAuthErr(e2) {
						status = "AUTH-BLOCKED (needs browser login; not probed)"
					} else {
						status = classify(resp2)
					}
				} else {
					status = "AUTH then timeout"
				}
			} else {
				status = classify(resp)
			}
		}
		summary = append(summary, fmt.Sprintf("%s -> %s", c.method, status))
	}

	// 3. raw notification window.
	fmt.Printf("--- collecting notifications for %s ---\n", collect)
	timer := time.NewTimer(collect)
collecting:
	for {
		select {
		case m, ok := <-read:
			if !ok {
				fmt.Println("conn closed during collect")
				break collecting
			}
			handleNotif(conn, ctx, m)
		case <-timer.C:
			break collecting
		}
	}

	fmt.Println("=== PROBE SUMMARY ===")
	for _, s := range summary {
		fmt.Println(" -", s)
	}
	fmt.Println("=== PROBE DONE ===")
}

func handleNotif(conn *websocket.Conn, ctx context.Context, m rpcMsg) {
	method, _ := m["method"].(string)
	if m["id"] != nil { // agent -> client request
		if probeRaw {
			fmt.Printf("[raw-req] %s\n", trunc(string(mustJSON(m)), 12000))
		}
		switch method {
		case "session/request_permission":
			p, _ := m["params"].(rpcMsg)
			opts, _ := p["options"].([]any)
			tc, _ := p["toolCall"].(rpcMsg)
			fmt.Printf("!!! PERMISSION REQUEST: %v\n", tc["title"])
			if len(opts) > 0 {
				o, _ := opts[0].(rpcMsg)
				fmt.Printf("    -> auto-approving first option: %v\n", o["name"])
				reply(conn, ctx, m["id"], rpcMsg{"outcome": rpcMsg{"outcome": "selected", "optionId": o["optionId"]}})
			} else {
				reply(conn, ctx, m["id"], rpcMsg{"outcome": rpcMsg{"outcome": "cancelled"}})
			}
		default:
			reply(conn, ctx, m["id"], nil)
			fmt.Println("??? agent request:", method)
		}
		return
	}
	if probeRaw { // probe mode: dump every notification verbatim
		fmt.Printf("[raw] %s\n", trunc(string(mustJSON(m)), 12000))
		return
	}
	if method == "session/update" {
		p, _ := m["params"].(rpcMsg)
		u, _ := p["update"].(rpcMsg)
		switch u["sessionUpdate"] {
		case "agent_message_chunk":
			c, _ := u["content"].(rpcMsg)
			fmt.Printf("[chunk] %v", c["text"])
		case "agent_thought_chunk":
			c, _ := u["content"].(rpcMsg)
			fmt.Printf("[think] %v", c["text"])
		case "tool_call", "tool_call_update":
			fmt.Printf("[tool %v] %v status=%v\n", u["sessionUpdate"], u["title"], u["status"])
		case "plan":
			fmt.Println("[plan]")
		default:
			fmt.Printf("[update %v] %s\n", u["sessionUpdate"], trunc(string(mustJSON(u)), 600))
		}
		return
	}
	if strings.HasPrefix(method, "_cognition.ai/") {
		return // Devin extension noise
	}
	fmt.Println("[notif]", method)
}
