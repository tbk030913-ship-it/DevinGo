package main

import (
	"bytes"
	"context"
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

// An inbound frame over wsReadLimit must close the socket with 1009 and a
// readable reason WITHOUT killing the devin child — the session stays
// resumable across the reconnect the client is expected to make.
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

	oldDevin, oldDir, oldToken := *devinBin, *defaultDir, *token
	defer func() { *devinBin, *defaultDir, *token = oldDevin, oldDir, oldToken }()
	*devinBin = bin
	*defaultDir = dir
	*token = "" // authorized() short-circuits true

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
	// reconnect — even though the parked stdout/wait goroutines stay
	// with the live process.
	deadline := time.Now().Add(3 * time.Second)
	for activeConns.Load() != 0 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if n := activeConns.Load(); n != 0 {
		t.Fatalf("handler still pinned after close: %d conns", n)
	}

	m := regexp.MustCompile(`\[(\d+)\] devin acp started`).
		FindSubmatch(logBuf.Bytes())
	if m == nil {
		t.Fatalf("no spawned-pid log line:\n%s", logBuf.String())
	}
	pid, _ := strconv.Atoi(string(m[1]))
	t.Cleanup(func() { reap(pid) })

	// Assert before devinAlive: reaping the sleeper makes the wait
	// goroutine log "devin acp exited" into logBuf concurrently.
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
