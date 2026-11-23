package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// An inbound frame over wsReadLimit must close the socket with 1009 WITHOUT
// killing the resident devin process — sessions stay resumable across the
// reconnect the client is expected to make.
func TestOversizedMessageKeepsDevinAlive(t *testing.T) {
	// devin stand-in: ignores its "acp" arg and just stays alive.
	dir := t.TempDir()
	bin := filepath.Join(dir, "sleeper")
	if runtime.GOOS == "windows" {
		bin += ".exe"
	}
	src := filepath.Join(dir, "sleeper.go")
	if err := os.WriteFile(src, []byte(
		"package main\nimport \"time\"\nfunc main() { time.Sleep(5 * time.Minute) }\n",
	), 0o644); err != nil {
		t.Fatalf("write sleeper source: %v", err)
	}
	if out, err := exec.Command("go", "build", "-o", bin, src).CombinedOutput(); err != nil {
		t.Fatalf("build sleeper: %v\n%s", err, out)
	}

	oldDevin, oldDir, oldToken, oldHub := *devinBin, *defaultDir, *token, bridgeHub
	defer func() {
		*devinBin, *defaultDir, *token, bridgeHub = oldDevin, oldDir, oldToken, oldHub
	}()
	*devinBin = bin
	*defaultDir = dir
	*token = "" // authorized() short-circuits true
	bridgeHub = newHub()

	var logBuf bytes.Buffer
	oldLog := log.Writer()
	log.SetOutput(&logBuf)
	defer log.SetOutput(oldLog)

	srv := httptest.NewServer(http.HandlerFunc(acpHandler))
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(ctx,
		"ws"+strings.TrimPrefix(srv.URL, "http")+"/acp", nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.CloseNow()

	// Spawning is on-demand: an initialize request is what brings the
	// resident process up (the sleeper never answers — it just has to be
	// alive for the oversized path below).
	if err := conn.Write(ctx, websocket.MessageText,
		[]byte(`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}`)); err != nil {
		t.Fatalf("initialize write: %v", err)
	}
	deadline := time.Now().Add(10 * time.Second)
	m := regexp.MustCompile(`\[(\d+)\] devin acp started`)
	var pm [][]byte
	for time.Now().Before(deadline) {
		if pm = m.FindSubmatch(logBuf.Bytes()); pm != nil {
			break
		}
		time.Sleep(25 * time.Millisecond)
	}
	if pm == nil {
		t.Fatalf("no spawned-pid log line:\n%s", logBuf.String())
	}
	pid, _ := strconv.Atoi(string(pm[1]))
	t.Cleanup(func() { reap(pid) })

	if err := conn.Write(ctx, websocket.MessageText,
		make([]byte, wsReadLimit+1)); err != nil {
		t.Fatalf("write: %v", err)
	}
	_, _, err = conn.Read(ctx)
	if got := websocket.CloseStatus(err); got != websocket.StatusMessageTooBig {
		t.Fatalf("close status = %v, want %v (err=%v)",
			got, websocket.StatusMessageTooBig, err)
	}

	// The handler must unwind promptly — conn slot freed for the
	// reconnect.
	deadline = time.Now().Add(3 * time.Second)
	for activeConns.Load() != 0 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if n := activeConns.Load(); n != 0 {
		t.Fatalf("handler still pinned after close: %d conns", n)
	}

	// Assert before devinAlive: reaping the sleeper makes the watcher
	// log "devin acp process down" into logBuf concurrently.
	if !strings.Contains(logBuf.String(), "left running") {
		t.Fatalf("missing keep-alive log line:\n%s", logBuf.String())
	}
	if !devinAlive(pid) {
		t.Fatal("devin process was killed by the oversized message")
	}
}

// devinAlive reports whether pid is still running (pure check — reaping is
// reap's job).
func devinAlive(pid int) bool {
	if runtime.GOOS == "windows" {
		out, _ := exec.Command("tasklist", "/FO", "CSV", "/NH",
			"/FI", "PID eq "+strconv.Itoa(pid)).Output()
		return strings.Contains(string(out), fmt.Sprintf(",%q,", strconv.Itoa(pid)))
	}
	p, err := os.FindProcess(pid)
	return err == nil && p.Signal(syscall.Signal(0)) == nil
}

// resolvePushURL: -push/DEVINGO_PUSH_URL wins over bridge.json's push_url;
// nothing configured stays "" (push disabled by default).
func TestResolvePushURL(t *testing.T) {
	old := *pushURL
	t.Cleanup(func() { *pushURL = old })

	dir := t.TempDir()
	// os.UserConfigDir: %AppData% on Windows, $XDG_CONFIG_HOME elsewhere.
	t.Setenv("APPDATA", dir)
	t.Setenv("XDG_CONFIG_HOME", dir)

	*pushURL = ""
	if got := resolvePushURL(); got != "" {
		t.Fatalf("resolvePushURL with nothing configured = %q, want \"\"", got)
	}

	cfgPath := filepath.Join(dir, "DevinGo", "bridge.json")
	if err := os.MkdirAll(filepath.Dir(cfgPath), 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := os.WriteFile(cfgPath,
		[]byte(`{"token":"x","push_url":"https://ntfy.sh/topic"}`), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}
	if got := resolvePushURL(); got != "https://ntfy.sh/topic" {
		t.Fatalf("resolvePushURL config fallback = %q, want config value", got)
	}

	*pushURL = "https://ntfy.sh/flag-wins"
	if got := resolvePushURL(); got != "https://ntfy.sh/flag-wins" {
		t.Fatalf("resolvePushURL flag precedence = %q, want flag value", got)
	}
}

// Version dirs must sort numerically component-wise — lexical order ranks
// "3000.12.100" below "3000.12.57" and would pick a stale devin binary.
func TestCompareDevinVersion(t *testing.T) {
	cases := []struct {
		a, b string
		want int
	}{
		{"3000.12.100", "3000.12.57", 1},
		{"3000.12.57", "3000.12.100", -1},
		{"2.0", "1.9.9", 1},
		{"1.2", "1.2.0", -1},
		{"3000.12.57", "3000.12.57", 0},
	}
	for _, tc := range cases {
		a, oka := parseDevinVersion(tc.a)
		b, okb := parseDevinVersion(tc.b)
		if !oka || !okb {
			t.Fatalf("parseDevinVersion rejected %q / %q", tc.a, tc.b)
		}
		sign := 0
		if got := compareDevinVersion(a, b); got > 0 {
			sign = 1
		} else if got < 0 {
			sign = -1
		}
		if sign != tc.want {
			t.Errorf("compareDevinVersion(%s, %s) has sign %d, want %d", tc.a, tc.b, sign, tc.want)
		}
	}
	if _, ok := parseDevinVersion("latest"); ok {
		t.Error("non-numeric dir name parsed as a version")
	}
}

// slimModels lifts {id,name} leaves out of devin's nested/family-grouped
// models JSON, dedupes by id, and emits [] (not null) on no matches.
func TestSlimModels(t *testing.T) {
	raw := `{"families":[{"name":"Cognition","models":[
		{"modelId":"devin-1","name":"Devin","ctx":200000},
		{"id":"gpt-x","title":"GPT X","extra":{"id":"nested","name":"skip"}},
		{"modelId":"devin-1","name":"Devin dup"}
	]}],"other":[{"slug":"solo","label":"Solo"}]}`
	out, err := slimModels([]byte(raw))
	if err != nil {
		t.Fatalf("slimModels: %v", err)
	}
	var got []map[string]string
	if err := json.Unmarshal(out, &got); err != nil {
		t.Fatalf("slim output not JSON: %v", err)
	}
	want := []map[string]string{
		{"id": "devin-1", "name": "Devin"},
		{"id": "gpt-x", "name": "GPT X"},
		{"id": "solo", "name": "Solo"},
	}
	if fmt.Sprint(got) != fmt.Sprint(want) {
		t.Fatalf("slimModels = %v, want %v", got, want)
	}
	empty, err := slimModels([]byte(`{"x":1}`))
	if err != nil || string(empty) != "[]" {
		t.Fatalf("no-match input: out=%s err=%v, want []", empty, err)
	}
	if _, err := slimModels([]byte("not json")); err == nil {
		t.Fatal("malformed input must error")
	}
}

// reap force-kills the devin stand-in once the test is done with it.
func reap(pid int) {
	if runtime.GOOS == "windows" {
		_ = exec.Command("taskkill", "/T", "/F", "/PID",
			strconv.Itoa(pid)).Run()
		return
	}
	if p, err := os.FindProcess(pid); err == nil {
		_ = p.Kill()
	}
}
