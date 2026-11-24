// M9.2: ecosystem management endpoints — MCP server list/toggle/OAuth
// login, skills/rules/plugins browsing and `devin doctor`. All of them are
// thin shells over the installed `devin` CLI run through runDevinOutput
// (temp-file stdout so devin's grandchildren can't pin Wait), bounded by
// cliOpTimeout and the cliMaxOutput cap. The OAuth-login pair is the one
// stateful piece: `devin mcp login` blocks until the callback lands, so
// the bridge keeps the process (stdin open) and relays the callback URL
// the user pastes back — either to its stdin (manual-token flow) or to the
// loopback listener an agent-side OAuth flow opened on this machine.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"sync"
	"time"
)

const (
	// cliOpTimeout bounds a single `devin <sub>` call — mcp list spawning a
	// cold toolchain still stays inside it.
	cliOpTimeout = 30 * time.Second
	// cliDoctorTimeout is wider: doctor walks checks over the network and
	// can take a noticeable while on a cold auth cache.
	cliDoctorTimeout = 90 * time.Second
	// cliMaxOutput caps CLI text handed to the app — list/show payloads are
	// a few KB; a runaway dump still can't pin the phone.
	cliMaxOutput = 256 << 10
	// mcpLoginURLWait bounds the post-spawn poll for an auth URL in the
	// login process's output before the handler answers without one.
	mcpLoginURLWait = 15 * time.Second
	// mcpLoginLifetime caps one pending OAuth login — abandoning the sheet
	// must not leave a `devin mcp login` resident forever.
	mcpLoginLifetime = 4 * time.Minute
	// mcpAuthMaxBody bounds the toggle/login/callback JSON payloads.
	mcpAuthMaxBody = 8 << 10
	// mcpCallbackCap bounds the response text relayed back from the
	// loopback OAuth listener.
	mcpCallbackCap = 64 << 10
)

// cliNameRe bounds names passed as positional CLI args (mcp server names,
// skill/rule/plugin ids — `owner/repo` plugin labels included): anything
// outside this set would land on the CLI's flag parser or its usage error.
var cliNameRe = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._:@/+~-]{0,127}$`)

// cliAuthURLRe lifts the first absolute http(s) link out of CLI output —
// `devin mcp login` prints the provider's authorization URL to stdout
// (manual-token flow prints it and waits on stdin).
var cliAuthURLRe = regexp.MustCompile(`https?://[^\s"'<>]+`)

// errCliArg is the sentinel for rejected request inputs (400s).
var errCliArg = errors.New("invalid argument")

// runDevinCapped runs `devin <args...>` under the workspace dir and returns
// stdout trimmed to cliMaxOutput — same temp-file plumbing as models.go.
func runDevinCapped(ctx context.Context, dir string, args ...string) (string, error) {
	out, err := runDevinOutput(ctx, dir, args...)
	if len(out) > cliMaxOutput {
		out = out[:cliMaxOutput]
	}
	return string(out), err
}

// cliWorkspaceDir picks the directory a `devin` subcommand runs inside: an
// explicit ?sessionId|cwd resolves through the same hosted-workspace gate
// /fs uses (a bearer holder can't point the CLI at arbitrary dirs); no
// params → the bridge default.
func cliWorkspaceDir(r *http.Request) (string, error) {
	q := r.URL.Query()
	sid, cwdParam := q.Get("sessionId"), q.Get("cwd")
	if sid == "" && cwdParam == "" {
		return *defaultDir, nil
	}
	return bridgeHub.fsBrowseRoot(sid, cwdParam)
}

// writeCliError maps workspace sentinels plus generic CLI failures — the
// raw message is safe to surface (devin errors carry no credentials).
func writeCliError(w http.ResponseWriter, err error) {
	status := http.StatusInternalServerError
	switch {
	case errors.Is(err, errSessionNotHosted):
		status = http.StatusNotFound
	case errors.Is(err, errPathOutside), errors.Is(err, errCliArg),
		errors.Is(err, errOAuthEscaped):
		status = http.StatusBadRequest
	}
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]any{"error": err.Error()})
}

// cliGetGates applies the shared GET gates (auth + origin) and reports
// whether the request should proceed — the handlers early-return on false.
func cliGetGates(w http.ResponseWriter, r *http.Request) bool {
	if r.Method != http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		w.Write([]byte(`{"error":"method not allowed"}`))
		return false
	}
	if !authorized(r) {
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte(`{"error":"unauthorized"}`))
		return false
	}
	if !validOrigin(r) {
		rejectForbiddenOrigin(w)
		return false
	}
	return true
}

// cliPostGates is cliGetGates for POST (auth + origin only; bodies are
// MaxBytesReader'd by the caller).
func cliPostGates(w http.ResponseWriter, r *http.Request) bool {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		w.Write([]byte(`{"error":"method not allowed"}`))
		return false
	}
	if !authorized(r) {
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte(`{"error":"unauthorized"}`))
		return false
	}
	if !validOrigin(r) {
		rejectForbiddenOrigin(w)
		return false
	}
	return true
}

// cliItem is one normalized list row (mcp server, skill, rule, plugin)
// extracted from a `devin <x> list --format json` payload. Only the fields
// the phone needs are lifted; the raw text rides `output` regardless.
type cliItem struct {
	Name    string `json:"name"`
	Detail  string `json:"detail,omitempty"`
	Path    string `json:"path,omitempty"`
	Enabled *bool  `json:"enabled,omitempty"`
	Status  string `json:"status,omitempty"`
}

// devinList runs `devin <sub> list` in dir: `--json` is attempted first
// (verified on the shipped binary: `mcp list`/`skills list`/`doctor`
// take it; `rules`/`plugins` don't — the flag error just falls through
// to the bare-text run, whose table is the documented raw form anyway).
func devinList(ctx context.Context, dir string, sub ...string) (string, []cliItem, error) {
	args := append(append([]string{}, sub...), "--json")
	out, err := runDevinCapped(ctx, dir, args...)
	if err == nil {
		if items := cliItemsFromJSON(out); items != nil {
			return out, items, nil
		}
		// Flag accepted but a non-list payload — fall through to text so the
		// raw table still reaches the viewer.
	}
	if out, err = runDevinCapped(ctx, dir, sub...); err != nil {
		return "", nil, err
	}
	return out, nil, nil
}

// cliItemsFromJSON walks a decoded `devin <x> list` payload for objects
// carrying a name-ish key — the same tolerant leaf rule as slimModels, so
// array-in-object and family-grouped layouts both yield rows. Nil when the
// output isn't JSON or no named entry exists.
func cliItemsFromJSON(out string) []cliItem {
	var root any
	if json.Unmarshal([]byte(out), &root) != nil {
		return nil
	}
	var items []cliItem
	seen := map[string]bool{}
	var walk func(v any)
	walk = func(v any) {
		switch t := v.(type) {
		case map[string]any:
			name := firstString(t, "name", "title", "displayName", "label", "id", "key", "server")
			if name != "" {
				if !seen[name] {
					seen[name] = true
					it := cliItem{
						Name:   name,
						Detail: firstString(t, "description", "summary", "subtitle", "trigger", "provider", "version"),
						Path:   firstString(t, "path", "file", "filePath", "directory", "location", "skill_dir", "rulePath"),
						Status: firstString(t, "status", "state"),
					}
					if en, ok := t["enabled"].(bool); ok {
						it.Enabled = &en
					} else if dis, ok := t["disabled"].(bool); ok {
						en = !dis
						it.Enabled = &en
					}
					items = append(items, it)
				}
				return
			}
			for _, c := range t {
				walk(c)
			}
		case []any:
			for _, c := range t {
				walk(c)
			}
		}
	}
	walk(root)
	return items
}

// ---- /mcp/* --------------------------------------------------------------

// mcpListHandler is GET /mcp/list?sessionId|cwd — `devin mcp list` in the
// session's workspace (project .mcp.json merges into the user-level set).
// {items} carries normalized rows when the JSON layout parsed; `output`
// always has the raw text for the app's tolerant fallback.
func mcpListHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	if !cliGetGates(w, r) {
		return
	}
	dir, err := cliWorkspaceDir(r)
	if err != nil {
		writeCliError(w, err)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), cliOpTimeout)
	defer cancel()
	out, items, err := devinList(ctx, dir, "mcp", "list")
	if err != nil {
		writeCliError(w, err)
		return
	}
	json.NewEncoder(w).Encode(map[string]any{
		"items":  items,
		"output": out,
	})
}

// mcpToggleHandler is POST /mcp/toggle — {name, enabled, cwd?}. Runs
// `devin mcp enable|disable <name>`; only ever fired by an explicit tap on
// the app's per-server switch (M9.2's "kill the 4 stalling servers" fix).
func mcpToggleHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	if !cliPostGates(w, r) {
		return
	}
	var req struct {
		Name    string `json:"name"`
		Enabled bool   `json:"enabled"`
		Cwd     string `json:"cwd"`
	}
	r.Body = http.MaxBytesReader(w, r.Body, mcpAuthMaxBody)
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte(`{"error":"invalid request body"}`))
		return
	}
	if !cliNameRe.MatchString(req.Name) {
		writeCliError(w, errCliArg)
		return
	}
	dir := *defaultDir
	if req.Cwd != "" {
		var err error
		dir, err = bridgeHub.fsBrowseRoot("", req.Cwd)
		if err != nil {
			writeCliError(w, err)
			return
		}
	}
	sub := "enable"
	if !req.Enabled {
		sub = "disable"
	}
	ctx, cancel := context.WithTimeout(r.Context(), cliOpTimeout)
	defer cancel()
	out, err := runDevinCapped(ctx, dir, "mcp", sub, req.Name)
	if err != nil {
		writeCliError(w, err)
		return
	}
	json.NewEncoder(w).Encode(map[string]any{"ok": true, "output": out})
}

// mcpLogin is a `devin mcp login` process parked mid-OAuth — the CLI holds
// the flow open until the callback (or a pasted code on stdin) completes
// it. stdin stays reachable through authCallback.
type mcpLogin struct {
	name    string
	cmd     *exec.Cmd
	stdin   io.WriteCloser
	outPath string
	errPath string
	done    chan struct{} // closed on process exit
	started time.Time
	// waitErr is set before done closes — readers gated on <-done see it
	// (channel close orders the write).
	waitErr error
}

var (
	mcpLoginsMu sync.Mutex
	mcpLogins   = map[string]*mcpLogin{}
)

// mcpLoginSnapshot is the response shape for spawn and status queries.
func mcpLoginSnapshot(l *mcpLogin) map[string]any {
	out, errOut := l.outputs()
	done := false
	var exitErr string
	select {
	case <-l.done:
		done = true
		if l.waitErr != nil {
			exitErr = l.waitErr.Error()
		}
	default:
	}
	return map[string]any{
		"name":    l.name,
		"authUrl": cliAuthURLRe.FindString(out + "\n" + errOut),
		"output":  tail(out+errOut, 8<<10),
		"done":    done,
		"error":   exitErr,
	}
}

// outputs reads both capture files (bounded — the files only ever hold
// what a bounded CLI wrote, and reads stop at the cap regardless).
func (l *mcpLogin) outputs() (stdout string, stderr string) {
	if b, err := os.ReadFile(l.outPath); err == nil {
		stdout = tail(string(b), cliMaxOutput)
	}
	if b, err := os.ReadFile(l.errPath); err == nil {
		stderr = tail(string(b), cliMaxOutput)
	}
	return stdout, stderr
}

func tail(s string, max int) string {
	if len(s) > max {
		return s[len(s)-max:]
	}
	return s
}

// spawnMcpLogin starts `devin mcp login <name>` parked on stdin, output to
// temp files (runDevinOutput's grandchild lesson — a pipe would leak wait
// blocks and a blocking stdin is required anyway). --force-manual-token-
// flow is tried first — borrowed from `auth login`'s help text ("skip
// browser-based auth and paste a token manually"); `mcp login --help`
// doesn't list it, so an unknown flag may kill the process within the
// first few hundred ms — detect that and respawn plain.
func spawnMcpLogin(name, dir string) (*mcpLogin, error) {
	l, err := spawnMcpLoginArgs(name, dir, true)
	if err != nil {
		return nil, err
	}
	select {
	case <-l.done:
		if l.waitErr != nil {
			// Instant exit — almost always the flag-parse error; drop the
			// flag and respawn (a genuinely-finished login just re-exits).
			return spawnMcpLoginArgs(name, dir, false)
		}
		return l, nil // finished instantly (already signed in)
	case <-time.After(600 * time.Millisecond):
		return l, nil
	}
}

func spawnMcpLoginArgs(name, dir string, manualFlow bool) (*mcpLogin, error) {
	out, err := os.CreateTemp("", "devingo-mcplogin-*.out")
	if err != nil {
		return nil, err
	}
	errF, err := os.CreateTemp("", "devingo-mcplogin-*.err")
	if err != nil {
		out.Close()
		os.Remove(out.Name())
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), mcpLoginLifetime)
	args := []string{"mcp", "login", name}
	if manualFlow {
		args = append(args, "--force-manual-token-flow")
	}
	cmd := exec.CommandContext(ctx, *devinBin, args...)
	cmd.Dir = dir
	cmd.Stdout = out
	cmd.Stderr = errF
	stdin, err := cmd.StdinPipe()
	if err != nil {
		cancel()
		out.Close()
		errF.Close()
		os.Remove(out.Name())
		os.Remove(errF.Name())
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		cancel()
		stdin.Close()
		out.Close()
		errF.Close()
		os.Remove(out.Name())
		os.Remove(errF.Name())
		return nil, err
	}
	l := &mcpLogin{
		name:    name,
		cmd:     cmd,
		stdin:   stdin,
		outPath: out.Name(),
		errPath: errF.Name(),
		done:    make(chan struct{}),
		started: time.Now(),
	}
	go func() {
		l.waitErr = cmd.Wait()
		stdin.Close()
		out.Close()
		errF.Close()
		close(l.done)
		cancel()
		// Keep the files (and map entry) — the last output tail is the
		// result/error the status poll hands back. A re-login or process
		// exit replaces/cleans them.
	}()
	go func() {
		// File cleanup once the entry's usefulness window closes; a newer
		// login for the same name replaces the map entry and the timer
		// simply deletes stale files underneath.
		<-l.done
		time.Sleep(2 * time.Minute)
		os.Remove(l.outPath)
		os.Remove(l.errPath)
		mcpLoginsMu.Lock()
		if mcpLogins[l.name] == l {
			delete(mcpLogins, l.name)
		}
		mcpLoginsMu.Unlock()
	}()
	return l, nil
}

// mcpLoginHandler is POST /mcp/login — {name, cwd?}: spawns (or reuses) a
// pending OAuth login, then polls its output up to mcpLoginURLWait for the
// authorization URL the sheet opens in a browser. GET ?name= re-reads the
// same pending login's state without spawning.
func mcpLoginHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	if r.Method == http.MethodGet {
		mcpLoginStatus(w, r)
		return
	}
	if !cliPostGates(w, r) {
		return
	}
	var req struct {
		Name string `json:"name"`
		Cwd  string `json:"cwd"`
	}
	r.Body = http.MaxBytesReader(w, r.Body, mcpAuthMaxBody)
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte(`{"error":"invalid request body"}`))
		return
	}
	if !cliNameRe.MatchString(req.Name) {
		writeCliError(w, errCliArg)
		return
	}
	dir := *defaultDir
	if req.Cwd != "" {
		var err error
		dir, err = bridgeHub.fsBrowseRoot("", req.Cwd)
		if err != nil {
			writeCliError(w, err)
			return
		}
	}
	mcpLoginsMu.Lock()
	l := mcpLogins[req.Name]
	if l != nil {
		select {
		case <-l.done:
			l = nil // finished — spawn a fresh one
		default:
		}
	}
	if l == nil {
		var err error
		l, err = spawnMcpLogin(req.Name, dir)
		if err != nil {
			mcpLoginsMu.Unlock()
			writeCliError(w, err)
			return
		}
		mcpLogins[req.Name] = l
	}
	mcpLoginsMu.Unlock()

	// Poll for the auth URL: manual-token flows print it up-front, the
	// browser flow prints it after a transport probe — 15s covers both.
	deadline := time.Now().Add(mcpLoginURLWait)
	for {
		snap := mcpLoginSnapshot(l)
		if snap["authUrl"] != "" || snap["done"] == true ||
			time.Now().After(deadline) {
			json.NewEncoder(w).Encode(snap)
			return
		}
		select {
		case <-l.done:
		case <-time.After(250 * time.Millisecond):
		}
	}
}

// mcpLoginStatus is GET /mcp/login?name= — the sheet's refresh poll while
// a login is mid-flow (or after the callback was relayed).
func mcpLoginStatus(w http.ResponseWriter, r *http.Request) {
	if !cliGetGates(w, r) {
		return
	}
	name := r.URL.Query().Get("name")
	mcpLoginsMu.Lock()
	l := mcpLogins[name]
	mcpLoginsMu.Unlock()
	if l == nil {
		w.WriteHeader(http.StatusNotFound)
		w.Write([]byte(`{"error":"no pending login"}`))
		return
	}
	json.NewEncoder(w).Encode(mcpLoginSnapshot(l))
}

// loopbackOAuthURL reports whether u is an http(s) URL on this machine's
// loopback — the only place an OAuth localhost redirect can legitimately
// land for a bridge-hosted listener. Keeps a bearer holder from turning
// the relay into a LAN/Internet fetch (it's still a local read primitive
// on the bridge machine, which the token already implies).
func loopbackOAuthURL(u *url.URL) bool {
	if u.Scheme != "http" && u.Scheme != "https" {
		return false
	}
	host := u.Hostname()
	if strings.EqualFold(host, "localhost") {
		return true
	}
	ip := net.ParseIP(strings.Trim(host, "[]"))
	return ip != nil && ip.IsLoopback()
}

// mcpOAuthMaxRedirects caps the callback fetch's redirect chain.
const mcpOAuthMaxRedirects = 5

// errOAuthEscaped fires when a redirect would carry the callback fetch
// off loopback — the pasted URL's own gate must hold at every hop.
var errOAuthEscaped = errors.New("redirect left loopback")

// oauthCallbackClient fetches pasted OAuth callbacks: each redirect hop
// is re-checked against the loopback gate so a pasted URL can't bounce
// the relay into the LAN, and the chain is capped.
var oauthCallbackClient = &http.Client{
	CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if len(via) >= mcpOAuthMaxRedirects {
			return errCliArg
		}
		if !loopbackOAuthURL(req.URL) {
			return errOAuthEscaped
		}
		return nil
	},
}

// mcpAuthCallbackHandler is POST /mcp/authCallback — {name, url}: the
// pasted OAuth redirect lands here because the phone's browser can never
// reach the PC's 127.0.0.1 listener. Two deliveries:
//   - a live `devin mcp login` for <name> → the URL goes to its stdin
//     (manual-token flow consumes pasted input verbatim);
//   - no pending login → the URL is fetched loopback-side, feeding an
//     agent-opened OAuth listener (ACP authorizationPending listenerPort).
func mcpAuthCallbackHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	if !cliPostGates(w, r) {
		return
	}
	var req struct {
		Name string `json:"name"`
		URL  string `json:"url"`
	}
	r.Body = http.MaxBytesReader(w, r.Body, mcpAuthMaxBody)
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte(`{"error":"invalid request body"}`))
		return
	}
	pasted := strings.TrimSpace(req.URL)
	if pasted == "" || len(pasted) > 4096 {
		writeCliError(w, errCliArg)
		return
	}
	// The manual flow reads one line — a multi-line paste must not smuggle
	// extra input lines into the parked login's stdin. Windows clipboards
	// land CRLF: a \r tail would corrupt both stdin and url.Parse.
	pasted, _, _ = strings.Cut(pasted, "\n")
	pasted = strings.TrimRight(pasted, " \r\t")
	// Manual-token flow first: a pending login for this server owns stdin.
	mcpLoginsMu.Lock()
	l := mcpLogins[req.Name]
	mcpLoginsMu.Unlock()
	if l != nil {
		select {
		case <-l.done:
		default:
			if _, err := io.WriteString(l.stdin, pasted+"\n"); err == nil {
				json.NewEncoder(w).Encode(map[string]any{"ok": true, "via": "stdin"})
				return
			}
		}
	}
	u, err := url.Parse(pasted)
	if err != nil || !loopbackOAuthURL(u) {
		writeCliError(w, errCliArg)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	req2, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		writeCliError(w, err)
		return
	}
	resp, err := oauthCallbackClient.Do(req2)
	if err != nil {
		writeCliError(w, err)
		return
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, mcpCallbackCap))
	json.NewEncoder(w).Encode(map[string]any{
		"ok":     resp.StatusCode < 400,
		"via":    "fetch",
		"status": resp.StatusCode,
		"body":   string(body),
	})
}

// ---- skills / rules / plugins ---------------------------------------------

// cliListHandler builds the shared GET /{skills,rules,plugins}/list
// handlers — `devin <sub> list` in the resolved workspace, {items} when
// the JSON layout decoded, raw `output` always.
func cliListHandler(sub string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if !cliGetGates(w, r) {
			return
		}
		dir, err := cliWorkspaceDir(r)
		if err != nil {
			writeCliError(w, err)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), cliOpTimeout)
		defer cancel()
		out, items, err := devinList(ctx, dir, sub, "list")
		if err != nil {
			writeCliError(w, err)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{
			"items":  items,
			"output": out,
		})
	}
}

// cliShowHandler builds GET /{skills,rules}/show?name= — `devin <sub> show
// <name>` returns the entry's detail text (SKILL.md / rule body).
func cliShowHandler(sub string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if !cliGetGates(w, r) {
			return
		}
		name := r.URL.Query().Get("name")
		if !cliNameRe.MatchString(name) {
			writeCliError(w, errCliArg)
			return
		}
		dir, err := cliWorkspaceDir(r)
		if err != nil {
			writeCliError(w, err)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), cliOpTimeout)
		defer cancel()
		out, err := runDevinCapped(ctx, dir, sub, "show", name)
		if err != nil {
			writeCliError(w, err)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"output": out})
	}
}

// ---- doctor -----------------------------------------------------------------

// doctorHandler is GET /doctor — `devin doctor` in the bridge's default
// dir, raw output under the wider doctor timeout (M9.2 环境诊断).
func doctorHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	if !cliGetGates(w, r) {
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), cliDoctorTimeout)
	defer cancel()
	out, err := runDevinCapped(ctx, *defaultDir, "doctor")
	if err != nil {
		// doctor exits non-zero when a check fails — the output is still
		// the useful part, so surface it as a 200 with the error string.
		json.NewEncoder(w).Encode(map[string]any{
			"output": fmt.Sprintf("%s\n%s", out, err.Error()),
			"error":  err.Error(),
		})
		return
	}
	json.NewEncoder(w).Encode(map[string]any{"output": out})
}
