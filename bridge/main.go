// Command devingo-bridge relays ACP (Agent Client Protocol) JSON-RPC messages
// between WebSocket clients and a local `devin acp` subprocess over stdio.
//
// Each WebSocket connection to /acp spawns its own `devin acp` process:
//
//	Android app --WS text frames--> bridge --stdin--> devin acp
//	Android app <--WS text frames-- bridge <--stdout-- devin acp
//
// ACP over stdio is newline-delimited JSON, so the bridge simply maps one
// WS text message to one stdin line and vice versa.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
)

var (
	addr       = flag.String("addr", ":8790", "listen address")
	devinBin   = flag.String("devin", "devin", "devin CLI binary name or absolute path")
	defaultDir = flag.String("cwd", "", "working directory for spawned devin processes (default: bridge's own cwd)")
	token      = flag.String("token", os.Getenv("DEVINGO_TOKEN"), "bearer token clients must present (or DEVINGO_TOKEN); empty = no auth")
	insecure   = flag.Bool("insecure", false, "allow listening on a non-loopback address without a bearer token")
	model      = flag.String("model", os.Getenv("DEVIN_MODEL"), "default model for new ACP sessions (devin acp --model / DEVIN_MODEL)")
	devinKey   = flag.String("devinkey", os.Getenv("DEVIN_API_KEY"), "api key injected into ACP `authenticate` for spawned devin processes; default reads windsurf_api_key from devin's credentials.toml")
)

const (
	// maxActiveConns caps concurrent /acp sessions: each one spawns a
	// `devin acp` process, so unbounded concurrency would let clients
	// fork-bomb the host.
	maxActiveConns = 8
	// wsReadLimit bounds a single inbound WS message. ACP payloads
	// (session transcripts, file contents) exceed the 32KiB default.
	wsReadLimit = 4 << 20
	// wsPingInterval/wsPingTimeout detect half-open TCP connections: a
	// sleeping phone leaves the socket dead but unclosed, which would
	// otherwise wedge devin's stdout once the pipe buffer fills.
	wsPingInterval = 25 * time.Second
	wsPingTimeout  = 10 * time.Second
)

var activeConns atomic.Int64

func acquireConn() bool {
	for {
		n := activeConns.Load()
		if n >= maxActiveConns {
			return false
		}
		if activeConns.CompareAndSwap(n, n+1) {
			return true
		}
	}
}

func releaseConn() { activeConns.Add(-1) }

func main() {
	flag.Parse()

	if *defaultDir == "" {
		if wd, err := os.Getwd(); err == nil {
			*defaultDir = wd
		}
	}
	if *devinBin == "devin" {
		if resolved := resolveVersionedDevin(); resolved != "" {
			log.Printf("devin shim detected; using versioned binary directly: %s", resolved)
			*devinBin = resolved
		}
	}
	if _, err := exec.LookPath(*devinBin); err != nil {
		log.Printf("warning: %q not found on PATH: %v (spawned connections will fail until -devin points at a valid binary)", *devinBin, err)
	}
	if !isLoopbackBind(*addr) && *token == "" && !*insecure {
		log.Fatalf("refusing to listen on %q without authentication: clients can drive the local Devin agent.\n"+
			"run with -token <secret> (or DEVINGO_TOKEN) to require a bearer token,\n"+
			"-addr 127.0.0.1:8790 for local-only use, or -insecure to explicitly allow unauthenticated LAN access", *addr)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", healthHandler)
	mux.HandleFunc("/acp", acpHandler)
	mux.HandleFunc("/sessions", sessionsHandler)
	mux.HandleFunc("/models", modelsHandler)
	mux.HandleFunc("/pair", pairHandler)
	mux.HandleFunc("/pair/qr.png", pairQRHandler)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		fmt.Fprintln(w, "DevinGo bridge")
		fmt.Fprintln(w, "  GET /healthz   - bridge + devin status")
		fmt.Fprintln(w, "  GET /sessions  - session list (`devin list --format json`)")
		fmt.Fprintln(w, "  GET /models    - model list (`devin models list --format json`)")
		fmt.Fprintln(w, "  WS  /acp       - ACP JSON-RPC relay")
	})

	printBanner()
	printPairQR()
	srv := &http.Server{
		Addr:              *addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("listening on %s", *addr)
	log.Fatal(srv.ListenAndServe())
}

func printBanner() {
	log.Printf("devin binary : %s", *devinBin)
	log.Printf("default cwd  : %s", *defaultDir)
	if *token == "" {
		log.Printf("auth         : DISABLED (set -token or DEVINGO_TOKEN to require a bearer token)")
	} else {
		log.Printf("auth         : bearer token required")
	}
	if ips := lanIPs(); len(ips) > 0 {
		for _, ip := range ips {
			port := strings.TrimPrefix(*addr, ":")
			if !strings.HasPrefix(*addr, ":") {
				_, port, _ = net.SplitHostPort(*addr)
			}
			log.Printf("  phone URL  : ws://%s:%s/acp", ip, port)
		}
	}
}

func lanIPs() []string {
	var out []string
	ifaces, err := net.Interfaces()
	if err != nil {
		return out
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			var ip net.IP
			switch v := a.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip == nil || ip.IsLoopback() {
				continue
			}
			if ip4 := ip.To4(); ip4 != nil {
				out = append(out, ip4.String())
			}
		}
	}
	return out
}

// resolveVersionedDevin locates %LOCALAPPDATA%\devin\cli\_versions\<newest>\
// bin\devin.exe. The shim at cli\bin\devin.exe re-delegates through a
// supervisor process whose death takes every `devin acp` child down with it
// ("Parent process exited" self-terminate); spawning the real binary keeps
// the bridge as the direct parent so children only die when we die.
func resolveVersionedDevin() string {
	if runtime.GOOS != "windows" {
		return ""
	}
	local := os.Getenv("LOCALAPPDATA")
	if local == "" {
		return ""
	}
	versionsDir := filepath.Join(local, "devin", "cli", "_versions")
	entries, err := os.ReadDir(versionsDir)
	if err != nil {
		return ""
	}
	var newest string
	for _, e := range entries {
		if e.IsDir() && e.Name() > newest {
			newest = e.Name()
		}
	}
	if newest == "" {
		return ""
	}
	exe := filepath.Join(versionsDir, newest, "bin", "devin.exe")
	if _, err := os.Stat(exe); err != nil {
		return ""
	}
	return exe
}

func isLoopbackBind(addr string) bool {
	host, _, err := net.SplitHostPort(addr)
	if err != nil || host == "" {
		return false
	}
	if strings.EqualFold(host, "localhost") {
		return true
	}
	ip := net.ParseIP(strings.Trim(host, "[]"))
	return ip != nil && ip.IsLoopback()
}

func authorized(r *http.Request) bool {
	if *token == "" {
		return true
	}
	if r.URL.Query().Get("token") == *token {
		return true
	}
	// RFC 9110: the auth scheme is case-insensitive.
	h := strings.TrimSpace(r.Header.Get("Authorization"))
	if len(h) > len("Bearer ") && strings.EqualFold(h[:len("Bearer ")], "Bearer ") {
		return strings.TrimSpace(h[len("Bearer "):]) == *token
	}
	return false
}

// validOrigin rejects browser cross-site WebSocket hijacking (CSWSH): a
// hostile page can otherwise reach the bridge via DNS rebinding even when it
// binds to localhost. Native apps send no Origin header and pass through.
func validOrigin(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}
	u, err := url.Parse(origin)
	if err != nil {
		return false
	}
	host := u.Hostname()
	if strings.EqualFold(host, "localhost") {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	if !authorized(r) {
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte(`{"error":"unauthorized"}`))
		return
	}
	_, lookErr := exec.LookPath(*devinBin)
	json.NewEncoder(w).Encode(map[string]any{
		"ok":          lookErr == nil,
		"devin":       *devinBin,
		"devinFound":  lookErr == nil,
		"defaultCwd":  *defaultDir,
		"authEnabled": *token != "",
		"time":        time.Now().Format(time.RFC3339),
	})
}

// sessionsHandler proxies `devin list --format json` so the app can show
// past sessions and offer resume. The cwd query param selects the project
// directory the CLI scopes the listing to.
func sessionsHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	if !authorized(r) {
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte(`{"error":"unauthorized"}`))
		return
	}
	cwd := r.URL.Query().Get("cwd")
	if cwd == "" {
		cwd = *defaultDir
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	out, err := runDevinOutput(ctx, cwd, "list", "--format", "json")
	if err != nil {
		w.WriteHeader(http.StatusBadGateway)
		json.NewEncoder(w).Encode(map[string]any{"error": err.Error()})
		return
	}
	trimmed := strings.TrimSpace(string(out))
	if trimmed == "" {
		trimmed = "[]"
	}
	w.Write([]byte(trimmed))
}

// modelsHandler proxies `devin models list --format json`. Unlike `devin
// list`, this command needs CLI credentials (`devin auth login`); when the
// CLI is not logged in it returns 502 and the app hides the model picker.
func modelsHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	if !authorized(r) {
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte(`{"error":"unauthorized"}`))
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	out, err := runDevinOutput(ctx, *defaultDir, "models", "list", "--format", "json")
	if err != nil {
		w.WriteHeader(http.StatusBadGateway)
		json.NewEncoder(w).Encode(map[string]any{"error": err.Error()})
		return
	}
	trimmed := strings.TrimSpace(string(out))
	if trimmed == "" {
		trimmed = "[]"
	}
	w.Write([]byte(trimmed))
}

// runDevinOutput runs `devin <args...>` and returns its stdout. Output goes
// through a temp file rather than a pipe: devin spawns grandchildren
// (supervisor, service helpers) that inherit the handles, and Go's Output()
// would block until every inheritor exits. A file handle lets Wait() return
// as soon as devin itself is done.
func runDevinOutput(ctx context.Context, dir string, args ...string) ([]byte, error) {
	f, err := os.CreateTemp("", "devingo-devin-*.out")
	if err != nil {
		return nil, err
	}
	defer os.Remove(f.Name())
	defer f.Close()

	ef, err := os.CreateTemp("", "devingo-devin-*.err")
	if err != nil {
		return nil, err
	}
	defer os.Remove(ef.Name())
	defer ef.Close()

	cmd := exec.CommandContext(ctx, *devinBin, args...)
	cmd.Dir = dir
	cmd.Stdout = f
	cmd.Stderr = ef
	if err := cmd.Run(); err != nil {
		// Surface the first 2KiB of stderr: devin reports auth failures,
		// missing credentials and crash causes there, while the bare exit
		// status alone ("exit status 1") is unactionable.
		if _, serr := ef.Seek(0, io.SeekStart); serr == nil {
			se, _ := io.ReadAll(io.LimitReader(ef, 2<<10))
			if msg := strings.TrimSpace(string(se)); msg != "" {
				return nil, fmt.Errorf("%w: %s", err, msg)
			}
		}
		return nil, err
	}
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return nil, err
	}
	return io.ReadAll(f)
}

// acpHandler upgrades to WebSocket, spawns `devin acp`, and pipes frames
// to stdin lines and stdout lines back to frames until either side dies.
func acpHandler(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	if !validOrigin(r) {
		http.Error(w, "forbidden origin", http.StatusForbidden)
		return
	}
	if !acquireConn() {
		log.Printf("acp: rejecting %s: %d/%d sessions active", r.RemoteAddr, activeConns.Load(), maxActiveConns)
		http.Error(w, "too many concurrent sessions", http.StatusServiceUnavailable)
		return
	}
	defer releaseConn()
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		// Origin is pre-checked above; native apps send no Origin header.
		InsecureSkipVerify: true,
	})
	if err != nil {
		log.Printf("ws accept: %v", err)
		return
	}
	conn.SetReadLimit(wsReadLimit)
	var closeOnce sync.Once
	// closeConn is the sole conn.Close wrapper; closeOnce dedups the tail,
	// the ping watchdog and fail() call sites. fail() runs before any
	// goroutine starts; after that, close always goes through conn.Close's
	// handshake and TCP teardown is what unblocks the other goroutines.
	closeConn := func(code websocket.StatusCode, reason string) {
		closeOnce.Do(func() { conn.Close(code, reason) })
	}

	acpArgs := []string{"acp"}
	if *model != "" {
		acpArgs = append(acpArgs, "--model", *model)
	}
	cmd := exec.Command(*devinBin, acpArgs...)
	cmd.Dir = *defaultDir
	cmd.Stderr = &stderrLogger{}
	stdin, err := cmd.StdinPipe()
	if err != nil {
		fail(closeConn, "stdin pipe: %v", err)
		return
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		fail(closeConn, "stdout pipe: %v", err)
		return
	}
	if err := cmd.Start(); err != nil {
		fail(closeConn, "start %s acp: %v", *devinBin, err)
		return
	}
	pid := cmd.Process.Pid
	log.Printf("[%d] devin acp started for %s", pid, r.RemoteAddr)
	defer func() {
		killProcessTree(cmd)
		log.Printf("[%d] devin acp stopped", pid)
	}()

	// Everything below races to finish; first failure wins and tears down
	// the connection and the subprocess.
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	// exitVerdict reports how `devin acp` exited: "" = expected teardown
	// (we killed it after ctx was cancelled), anything else = it died on
	// its own while the session was still live, with the reason.
	exitVerdict := make(chan string, 1)

	// auto-auth state: once the client's initialize request is seen the gate
	// arms; the injected authenticate resolves it. Client frames wait on
	// authDone while armed so session/new can't race past the auth reply.
	var stdinMu sync.Mutex
	authGate := make(chan struct{})
	var gateOpen sync.Once
	var initID atomic.Value // json.RawMessage of the client's initialize id
	var gateArmed atomic.Bool
	apiKey := devinCredentialsKey()
	closeGate := func() {
		gateArmed.Store(false)
		gateOpen.Do(func() { close(authGate) })
	}
	if apiKey == "" {
		closeGate() // nothing to inject; never gate
	}

	var wg sync.WaitGroup
	wg.Add(4)

	// devin stdout -> WebSocket
	go func() {
		defer wg.Done()
		br := bufio.NewReaderSize(stdout, 1<<20)
		for {
			line, err := br.ReadBytes('\n')
			trimmed := strings.TrimRight(string(line), "\r\n")
			if trimmed != "" {
				bline := []byte(trimmed)
				head := rpcParse(bline)
				if len(head.ID) > 0 {
					id := string(head.ID)
					if id == bridgeAuthID {
						// The injected authenticate's reply: swallow it
						// and release queued client frames.
						log.Printf("[%d] devin auth injected: %s", pid, trimmed[:min(len(trimmed), 160)])
						closeGate()
						gateArmed.Store(false)
						if err == nil {
							continue
						}
					} else if v := initID.Load(); v != nil && id == string(v.(json.RawMessage)) {
						// The initialize response just went through —
						// inject authenticate unless devin asks for none.
						if apiKey != "" && len(head.Result.AuthMethods) > 0 {
							msg := authRequestLine(apiKey)
							stdinMu.Lock()
							_, werr := io.WriteString(stdin, string(msg)+"\n")
							stdinMu.Unlock()
							if werr != nil {
								closeGate()
							} else {
								time.AfterFunc(30*time.Second, closeGate) // fail-open
							}
						} else {
							closeGate()
						}
					}
				}
				// conn ops run on r.Context(), not the session ctx: a
				// session-ctx op's timeout would hard-drop the TCP
				// connection on cancel() and no close frame could
				// reach the client afterwards.
				if werr := conn.Write(r.Context(), websocket.MessageText, bline); werr != nil {
					cancel()
					return
				}
			}
			if err != nil {
				// stdout EOF means devin is exiting; do NOT cancel
				// here — the wait goroutine must see whether ctx was
				// still live at process death to classify the exit.
				return
			}
		}
	}()

	// WebSocket -> devin stdin
	go func() {
		defer wg.Done()
		defer cancel()
		for {
			typ, data, err := conn.Read(r.Context())
			if err != nil {
				return
			}
			if typ != websocket.MessageText {
				continue // ACP is NDJSON text; ignore binary frames
			}
			head := rpcParse(data)
			if gateArmed.Load() {
				select {
				case <-authGate:
				case <-ctx.Done():
					return
				}
			}
			// Arm only after the check: the initialize frame itself must
			// pass through for devin to produce the response we hook.
			if head.Method == "initialize" && len(head.ID) > 0 {
				initID.Store(head.ID)
				gateArmed.Store(true)
			}
			stdinMu.Lock()
			_, err = io.WriteString(stdin, string(data)+"\n")
			stdinMu.Unlock()
			if err != nil {
				return
			}
		}
	}()

	// Half-open connection probe: a peer that vanishes without a TCP FIN
	// (phone asleep, network drop) would keep the session — and the devin
	// process — alive forever, with conn.Write eventually blocking on a
	// full send buffer. The pong is read by the read goroutine above; a
	// missed pong means the peer is gone.
	go func() {
		defer wg.Done()
		ticker := time.NewTicker(wsPingInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
			}
			pctx, pcancel := context.WithTimeout(ctx, wsPingTimeout)
			err := conn.Ping(pctx)
			pcancel()
			if err != nil {
				if ctx.Err() != nil {
					return // tearing down already; not a dead peer
				}
				log.Printf("[%d] ws ping failed, dropping half-open session: %v", pid, err)
				// Close before cancel, same as the wait goroutine below.
				closeConn(websocket.StatusInternalError, "ping timeout")
				cancel()
				return
			}
		}
	}()

	// Process exit -> report verdict + tear down
	go func() {
		defer wg.Done()
		err := cmd.Wait()
		if err != nil && !errors.Is(err, os.ErrProcessDone) {
			log.Printf("[%d] devin acp exited: %v", pid, err)
		}
		// The only expected exits follow ctx cancellation (that is what
		// triggers killProcessTree in the tail). If the process left
		// while the session was still live it died on its own — a crash
		// or self-termination the client must be told about. Genuine
		// races (conn died in the same instant) can go either way.
		verdict := ""
		if ctx.Err() == nil {
			verdict = "devin acp exited"
			if err != nil {
				verdict = fmt.Sprintf("devin acp exited: %v", err)
			}
		}
		exitVerdict <- verdict
		cancel()
	}()

	<-ctx.Done()
	// Kill first: the stdout goroutine blocks on ReadBytes and the wait
	// goroutine on cmd.Wait() until the process exits, so killing is what
	// unblocks them and guarantees the verdict below has been sent.
	killProcessTree(cmd)
	code := websocket.StatusNormalClosure
	reason := "session ended"
	if verdict := <-exitVerdict; verdict != "" {
		// StatusAbnormalClosure (1006) is reserved and cannot be sent in
		// a close frame; StatusInternalError is the closest sendable
		// "the agent, not the client, ended the session" signal.
		code = websocket.StatusInternalError
		reason = verdict
	}
	closeConn(code, closeReason(reason))
	wg.Wait()
}

// closeReason truncates s to the 123-byte close-frame reason limit and
// keeps it valid UTF-8 for the peer.
func closeReason(s string) string {
	if len(s) > 123 {
		s = s[:123]
	}
	return strings.ToValidUTF8(s, "")
}

func fail(closeConn func(websocket.StatusCode, string), format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	log.Print(msg)
	closeConn(websocket.StatusInternalError, msg)
}

func killProcessTree(cmd *exec.Cmd) {
	if cmd == nil || cmd.Process == nil {
		return
	}
	if runtime.GOOS == "windows" {
		// Kill() alone leaves grandchildren (MCP servers etc.) behind.
		exec.Command("taskkill", "/T", "/F", "/PID", strconv.Itoa(cmd.Process.Pid)).Run()
		return
	}
	cmd.Process.Kill()
}

// --- bridge-side auto-authentication -------------------------------------
//
// `devin acp` treats the ACP host as the sole credential source — local CLI
// credentials are explicitly ignored, so every spawned process demands an
// `authenticate` call before session/new. Without injection the phone would
// be bounced into a browser login on every session. The bridge injects an
// `authenticate` request right after forwarding the client's `initialize`
// response, holds client frames until it resolves, and swallows the reply.
// If the key is missing or rejected the relay simply carries on and the
// client falls back to its own login flow.

const bridgeAuthID = `"bridge-auto-auth"`

// devinCredentialsKey returns the api key used for injected `authenticate`
// calls: the -devinkey/DEVIN_API_KEY flag wins, else the windsurf_api_key
// stored in devin's credentials.toml.
func devinCredentialsKey() string {
	if *devinKey != "" {
		return *devinKey
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

type rpcHead struct {
	ID     json.RawMessage `json:"id"`
	Method string          `json:"method"`
	Result struct {
		AuthMethods []json.RawMessage `json:"authMethods"`
	} `json:"result"`
}

// rpcParse pulls the envelope fields the relay needs: id (raw), method, and
// authMethods when the line is an initialize result.
func rpcParse(line []byte) rpcHead {
	var h rpcHead
	_ = json.Unmarshal(line, &h)
	return h
}

// authRequestLine builds the NDJSON line for the injected authenticate call.
func authRequestLine(apiKey string) []byte {
	b, _ := json.Marshal(map[string]any{
		"jsonrpc": "2.0",
		"id":      json.RawMessage(bridgeAuthID),
		"method":  "authenticate",
		"params": map[string]any{
			"methodId": "devin-browser",
			"_meta":    map[string]any{"api_key": apiKey},
		},
	})
	return b
}

// stderrLogger forwards devin's stderr lines to the bridge log. devin's
// own diagnostics also land in %APPDATA%\devin\cli\logs.
type stderrLogger struct{ mu sync.Mutex }

func (l *stderrLogger) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	for _, line := range strings.Split(strings.TrimRight(string(p), "\n"), "\n") {
		if line != "" {
			log.Printf("[devin] %s", line)
		}
	}
	return len(p), nil
}

var _ io.Writer = (*stderrLogger)(nil)
