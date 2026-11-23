// Command devingo-bridge hosts ACP (Agent Client Protocol) sessions between
// WebSocket clients and ONE resident `devin acp` subprocess over stdio:
//
//	Android app(s) --WS text frames--> bridge --stdin--> devin acp
//	Android app(s) <--WS text frames-- bridge <--stdout-- devin acp
//
// The bridge's Hub (hub.go) owns the process, journals every session's
// events into a sequence-numbered ring buffer, and fans frames out to
// subscribed clients — so a dropped phone reconnects and catches up without
// losing the running turn. Plain-ACP clients are unaffected: initialize is
// answered from the gateway's cache and ids are transparently rewritten.
package main

import (
	"context"
	"crypto/rand"
	"encoding/base64"
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
	token      = flag.String("token", os.Getenv("DEVINGO_TOKEN"), "bearer token clients must present (or DEVINGO_TOKEN); default: auto-generated, persisted in the user config dir")
	insecure   = flag.Bool("insecure", false, "disable remote-auth enforcement: without an explicit -token no token is generated (opt-out for unauthenticated LAN access)")
	install    = flag.Bool("install", false, "register a per-user scheduled task that starts the bridge at logon, then exit")
	uninstall  = flag.Bool("uninstall", false, "remove the logon scheduled task, then exit")
	model      = flag.String("model", os.Getenv("DEVIN_MODEL"), "default model for new ACP sessions (devin acp --model / DEVIN_MODEL)")
	devinKey   = flag.String("devinkey", os.Getenv("DEVIN_API_KEY"), "api key injected into ACP `authenticate` for spawned devin processes; default reads windsurf_api_key from devin's credentials.toml")
	idleTTL    = flag.Duration("idle-ttl", 10*time.Minute, "grace period a session with no subscribers/running work/pending approvals stays hosted before the hub releases it")
	permTTL    = flag.Duration("perm-ttl", 30*time.Minute, "how long an unanswered agent permission request stays pending before the gateway cancels it")
	pushURL    = flag.String("push", os.Getenv("DEVINGO_PUSH_URL"), "offline-push endpoint (e.g. https://ntfy.sh/<topic> or a compatible webhook): POSTs approval/turn-end pings when no client watches the session; default off (or DEVINGO_PUSH_URL / bridge.json push_url)")
)

const (
	// maxActiveConns caps concurrent /acp WebSocket connections.
	maxActiveConns = 8
	// wsReadLimit bounds a single inbound WS message. ACP payloads
	// (session transcripts, file contents) exceed the 32KiB default.
	wsReadLimit = 4 << 20
	// wsPingInterval/wsPingTimeout detect half-open TCP connections: a
	// sleeping phone leaves the socket dead but unclosed.
	wsPingInterval = 25 * time.Second
	wsPingTimeout  = 10 * time.Second
	// orphanReapDelay is the shortened idle grace for sessions orphaned by
	// an oversized inbound frame (B2): long enough for a re-attach, short
	// enough that a poisoned transcript doesn't linger.
	orphanReapDelay = 5 * time.Minute
)

// bridgeHub is the process-wide session hub (swapped out wholesale in tests).
var bridgeHub = newHub()

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

	if *install {
		installLogonTask()
		return
	}
	if *uninstall {
		uninstallLogonTask()
		return
	}

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
	// A bearer token is mandatory on non-loopback binds. resolveToken always
	// returns one — flag/env, the persisted token, or a freshly minted one —
	// so "" here means the operator opted out with -insecure.
	*token = resolveToken()
	if *token == "" {
		log.Print("!!! WARNING: -insecure set, bearer-token auth DISABLED")
		log.Print("!!! Any device that can reach this bridge can drive the local Devin agent.")
		log.Print("!!! Omit -insecure to use the auto-generated token, or bind to 127.0.0.1.")
	}

	bridgePush = newPushNotifier(resolvePushURL())
	go startModelsCache()

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", healthHandler)
	mux.HandleFunc("/acp", acpHandler)
	mux.HandleFunc("/models", modelsHandler)
	mux.HandleFunc("/fs/write", fsWriteHandler)
	mux.HandleFunc("/pair", pairHandler)
	mux.HandleFunc("/pair/qr.png", pairQRHandler)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		fmt.Fprintln(w, "DevinGo bridge")
		fmt.Fprintln(w, "  GET /healthz   - bridge + devin status")
		fmt.Fprintln(w, "  GET /models    - cached model list (`devin models list`, refreshed periodically)")
		fmt.Fprintln(w, "  POST /fs/write - write a file inside a session workspace")
		fmt.Fprintln(w, "  WS  /acp       - ACP session host (plain ACP + _devingo/* extensions)")
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
		log.Printf("auth         : DISABLED (-insecure)")
	} else {
		log.Printf("auth         : bearer token required for remote clients (loopback exempt)")
	}
	if bridgePush != nil {
		log.Printf("push         : %s (approval/turn-end pings)", bridgePush.url)
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
	var newestVer []int
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		v, ok := parseDevinVersion(e.Name())
		if !ok {
			continue // not a version dir (stray folder, differently-named dir)
		}
		if newestVer == nil || compareDevinVersion(v, newestVer) > 0 {
			newest, newestVer = e.Name(), v
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

// parseDevinVersion splits "3000.12.57"-style directory names into numeric
// components. Lexical name compare would rank "3000.12.100" below
// "3000.12.57" — the components must compare numerically.
func parseDevinVersion(name string) ([]int, bool) {
	segs := strings.Split(name, ".")
	parts := make([]int, len(segs))
	for i, seg := range segs {
		n, err := strconv.Atoi(seg)
		if err != nil {
			return nil, false
		}
		parts[i] = n
	}
	return parts, true
}

// compareDevinVersion orders two parsed versions component-wise; a longer
// version wins ties (1.2.0 > 1.2).
func compareDevinVersion(a, b []int) int {
	for i := 0; i < len(a) && i < len(b); i++ {
		if a[i] != b[i] {
			return a[i] - b[i]
		}
	}
	return len(a) - len(b)
}

// resolveToken picks the client bearer token: explicit -token/DEVINGO_TOKEN
// first, then the token persisted in bridge.json under the user config dir
// (stable across restarts, so paired phones keep working), else a freshly
// minted random one saved there. With -insecure and no explicit token it
// returns "" — auth disabled, the caller warns.
func resolveToken() string {
	if *token != "" || *insecure {
		return *token
	}
	path := bridgeConfigPath()
	if path != "" {
		if cfg := loadBridgeConfig(path); cfg.Token != "" {
			return cfg.Token
		}
	}
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		log.Fatalf("generate bridge token: %v", err)
	}
	tok := base64.RawURLEncoding.EncodeToString(raw)
	if path == "" {
		log.Print("warning: no user config dir; generated token is ephemeral to this run")
		return tok
	}
	cfg := loadBridgeConfig(path) // keep push_url & friends across the rewrite
	cfg.Token = tok
	b, _ := json.Marshal(cfg)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		log.Printf("warning: cannot persist bridge token: %v (ephemeral this run)", err)
	} else if err := os.WriteFile(path, b, 0o600); err != nil {
		log.Printf("warning: cannot persist bridge token to %s: %v (ephemeral this run)", path, err)
	} else {
		log.Printf("generated new bridge token, saved to %s", path)
	}
	return tok
}

// bridgeConfig is the persisted bridge.json shape under the user config
// dir: the minted bearer token plus the optional offline-push endpoint.
type bridgeConfig struct {
	Token   string `json:"token"`
	PushURL string `json:"push_url,omitempty"`
}

// loadBridgeConfig reads bridge.json; a missing or malformed file yields
// the zero config (each consumer treats its own field independently).
func loadBridgeConfig(path string) bridgeConfig {
	var cfg bridgeConfig
	if b, err := os.ReadFile(path); err == nil {
		_ = json.Unmarshal(b, &cfg)
	}
	return cfg
}

// resolvePushURL picks the offline-push endpoint: -push/DEVINGO_PUSH_URL
// wins, then bridge.json's push_url. "" keeps push disabled (the default).
func resolvePushURL() string {
	if *pushURL != "" {
		return *pushURL
	}
	if path := bridgeConfigPath(); path != "" {
		return loadBridgeConfig(path).PushURL
	}
	return ""
}

// bridgeConfigPath locates the persisted token file: %APPDATA%\DevinGo\
// bridge.json on Windows (os.UserConfigDir resolves AppData\Roaming), the
// os-native config dir elsewhere. "" when no config dir can be determined.
func bridgeConfigPath() string {
	dir, err := os.UserConfigDir()
	if err != nil {
		return ""
	}
	return filepath.Join(dir, "DevinGo", "bridge.json")
}

// schedTaskName is the Scheduled Tasks entry -install/-uninstall manage.
// It is deliberately a per-user logon task, not a service: spawned `devin
// acp` children read credentials under the user's profile, so the bridge
// must run inside the user's session.
const schedTaskName = "DevinGo Bridge"

func installLogonTask() {
	exe, err := os.Executable()
	if err != nil {
		log.Fatalf("locate exe: %v", err)
	}
	if exe, err = filepath.Abs(exe); err != nil {
		log.Fatalf("resolve exe path: %v", err)
	}
	// Pin the session cwd into the task: Task Scheduler would otherwise
	// start the exe under System32, making it the default project dir.
	dir := *defaultDir
	if dir == "" {
		if dir, err = os.Getwd(); err != nil {
			log.Fatalf("resolve working dir: %v", err)
		}
	}
	if dir, err = filepath.Abs(dir); err != nil {
		log.Fatalf("resolve working dir: %v", err)
	}
	cmd := exec.Command("schtasks", "/create", "/tn", schedTaskName,
		"/sc", "onlogon", "/rl", "limited", "/f",
		"/tr", fmt.Sprintf(`"%s" -cwd "%s"`, exe, dir))
	log.Printf("running: %s", cmd)
	if out, err := cmd.CombinedOutput(); err != nil {
		log.Fatalf("schtasks: %v\n%s", err, out)
	}
	log.Printf("installed logon task %q -> %s", schedTaskName, exe)
}

func uninstallLogonTask() {
	cmd := exec.Command("schtasks", "/delete", "/tn", schedTaskName, "/f")
	log.Printf("running: %s", cmd)
	if out, err := cmd.CombinedOutput(); err != nil {
		// Idempotent: a missing task is already the desired end state
		// (avoids parsing schtasks' localized "not found" text).
		if exec.Command("schtasks", "/query", "/tn", schedTaskName).Run() != nil {
			log.Printf("logon task %q not present; nothing to remove", schedTaskName)
			return
		}
		log.Fatalf("schtasks: %v\n%s", err, out)
	}
	log.Printf("removed logon task %q", schedTaskName)
}

// authorized gates the protected endpoints (/acp, /models). The
// token only guards the LAN surface: loopback clients stay trusted so local
// tooling keeps working without knowing the token.
func authorized(r *http.Request) bool {
	if *token == "" {
		return true
	}
	// Loopback exemption — but only when the request also names this
	// machine in Host. A DNS-rebound browser fetch lands on 127.0.0.1 yet
	// keeps its hostile Host header, so RemoteAddr alone is not proof of
	// a local client.
	if isLoopbackRemote(r) {
		host, _, err := net.SplitHostPort(r.Host)
		if err != nil {
			host = r.Host // Host may arrive without a port
		}
		if isLocalHost(host) {
			return true
		}
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

// isLocalHost reports whether a bare hostname (no port) names this machine:
// "localhost" or a loopback IP literal (with or without brackets).
func isLocalHost(host string) bool {
	if strings.EqualFold(host, "localhost") {
		return true
	}
	ip := net.ParseIP(strings.Trim(host, "[]"))
	return ip != nil && ip.IsLoopback()
}

// validOrigin rejects browser cross-site request forgery (CSWSH on /acp,
// drive-by spam on /models): a hostile page can otherwise
// reach the bridge via DNS rebinding even when it binds to localhost.
// Native apps and curl send no Origin header and pass through.
func validOrigin(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}
	u, err := url.Parse(origin)
	if err != nil {
		return false
	}
	return isLocalHost(u.Hostname())
}

// rejectForbiddenOrigin writes the shared 403 body for validOrigin failures.
func rejectForbiddenOrigin(w http.ResponseWriter) {
	w.WriteHeader(http.StatusForbidden)
	w.Write([]byte(`{"error":"forbidden origin"}`))
}

// healthHandler is intentionally unauthenticated: phones probe it during
// pairing to find a reachable bridge address before they hold the token.
func healthHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
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

// modelsHandler serves the cached model list (models.go — the gateway
// refreshes it in the background instead of spawning `devin models list`
// per request). Until the first fetch succeeds — e.g. the CLI isn't logged
// in (`devin auth login`) — it 502s and the app hides the model picker.
func modelsHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	if !authorized(r) {
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte(`{"error":"unauthorized"}`))
		return
	}
	if !validOrigin(r) {
		rejectForbiddenOrigin(w)
		return
	}
	out, err := modelsOrFetch()
	if err != nil {
		w.WriteHeader(http.StatusBadGateway)
		json.NewEncoder(w).Encode(map[string]any{"error": err.Error()})
		return
	}
	w.Write(out)
}

// fsWriteMaxBody bounds the JSON payload — plans are small text files.
const fsWriteMaxBody = 1 << 20

// fsWriteHandler writes a file inside a hosted session's workspace:
// {sessionId, path, content}. path must be relative — the hub resolves it
// against the cwd the session was created/loaded with and enforces
// containment (absolute paths and ".." traversal are 403s).
func fsWriteHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		w.Write([]byte(`{"error":"method not allowed"}`))
		return
	}
	if !authorized(r) {
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte(`{"error":"unauthorized"}`))
		return
	}
	if !validOrigin(r) {
		rejectForbiddenOrigin(w)
		return
	}
	var req struct {
		SessionID string `json:"sessionId"`
		Path      string `json:"path"`
		Content   string `json:"content"`
	}
	r.Body = http.MaxBytesReader(w, r.Body, fsWriteMaxBody)
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte(`{"error":"invalid request body"}`))
		return
	}
	if req.SessionID == "" || req.Path == "" {
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte(`{"error":"sessionId and path are required"}`))
		return
	}
	if err := bridgeHub.writeSessionFile(req.SessionID, req.Path, req.Content); err != nil {
		status := http.StatusInternalServerError
		switch {
		case errors.Is(err, errSessionNotHosted):
			status = http.StatusNotFound
		case errors.Is(err, errPathOutside):
			status = http.StatusForbidden
		}
		w.WriteHeader(status)
		json.NewEncoder(w).Encode(map[string]any{"error": err.Error()})
		return
	}
	json.NewEncoder(w).Encode(map[string]any{"ok": true, "path": req.Path})
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

// acpHandler upgrades a connection into a hub Client. Nothing is spawned or
// killed per connection: the resident devin process and the session
// journals belong to the hub, so a dropped socket only detaches its
// subscriptions.
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
		log.Printf("acp: rejecting %s: %d/%d conns active", r.RemoteAddr, activeConns.Load(), maxActiveConns)
		http.Error(w, "too many concurrent connections", http.StatusServiceUnavailable)
		return
	}
	defer releaseConn()
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		// Origin is pre-checked above; native apps send no Origin header.
		InsecureSkipVerify: true,
		// OkHttp offers permessage-deflate; the library default
		// (CompressionDisabled) would refuse to negotiate it.
		CompressionMode: websocket.CompressionNoContextTakeover,
	})
	if err != nil {
		log.Printf("ws accept: %v", err)
		return
	}
	conn.SetReadLimit(wsReadLimit)

	client := newClient(bridgeHub, conn, r.Context(), r.RemoteAddr)
	bridgeHub.addClient(client)
	done := make(chan struct{})
	defer close(done)
	go client.writeLoop()
	go client.pingLoop(done)

	readErr := client.readLoop()
	if readErr != nil {
		log.Printf("client %s read loop ended: %v (close status=%v)",
			client.id, readErr, websocket.CloseStatus(readErr))
	}
	reapTTL := time.Duration(0)
	if errors.Is(readErr, websocket.ErrMessageTooBig) {
		// The read limit already put a 1009 close on the wire; the session
		// stays hosted for a reconnect + re-attach (or session/load), then
		// is reaped on the shorter orphan delay rather than the idle TTL.
		log.Printf("client %s inbound frame exceeded %d-byte limit; devin acp left running",
			client.id, wsReadLimit)
		reapTTL = orphanReapDelay
	}
	bridgeHub.removeClient(client, reapTTL)
	client.kick(websocket.StatusNormalClosure, "bye")
	log.Printf("client %s (%s) disconnected", client.id, r.RemoteAddr)
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

// devinCredentialsKey returns the api key used for the gateway's own
// `authenticate` call: the -devinkey/DEVIN_API_KEY flag wins, else the
// windsurf_api_key stored in devin's credentials.toml.
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
		// Anchor on the key name before '=': a bare HasPrefix would also
		// match lookalike keys such as windsurf_api_key_backup.
		key, val, ok := strings.Cut(line, "=")
		if !ok || strings.TrimSpace(key) != "windsurf_api_key" {
			continue
		}
		return strings.Trim(strings.TrimSpace(val), `"'`)
	}
	return ""
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
