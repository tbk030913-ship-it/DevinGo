// /mcp/* + skills/rules/doctor endpoint tests (M9.2): JSON-mode list
// normalization, toggle arg mapping, name validation and the
// loopback-only auth-callback gate.
package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

// buildStubCLI builds a stub `devin` binary that echoes its args so the CLI
// handlers can be exercised without the real CLI. (hub_test.go's fakeDevin
// is the ACP stub — this one is the plain CLI stand-in.)
func buildStubCLI(t *testing.T, goSrc string) string {
	t.Helper()
	dir := t.TempDir()
	bin := filepath.Join(dir, "devin-stub")
	if runtime.GOOS == "windows" {
		bin += ".exe"
	}
	src := filepath.Join(dir, "main.go")
	if err := os.WriteFile(src, []byte(goSrc), 0o644); err != nil {
		t.Fatalf("write stub: %v", err)
	}
	if out, err := exec.Command("go", "build", "-o", bin, src).CombinedOutput(); err != nil {
		t.Fatalf("build stub: %v\n%s", err, out)
	}
	return bin
}

// mcpPost posts the JSON body and returns (status, decoded map).
func mcpPost(t *testing.T, srv *httptest.Server, path, body string) (int, map[string]any) {
	t.Helper()
	resp, err := http.Post(srv.URL+path, "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatalf("post %s: %v", path, err)
	}
	defer resp.Body.Close()
	var m map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&m)
	return resp.StatusCode, m
}

func TestCliItemsFromJSON(t *testing.T) {
	items := cliItemsFromJSON(`{"servers":[{"name":"zread","enabled":true,"status":"connected"},{"name":"gh","disabled":true,"description":"github"}]}`)
	if len(items) != 2 {
		t.Fatalf("items = %v", items)
	}
	if items[0].Name != "zread" || items[0].Enabled == nil || *items[0].Enabled != true {
		t.Fatalf("item0 = %+v", items[0])
	}
	if items[1].Name != "gh" || items[1].Enabled == nil || *items[1].Enabled != false {
		t.Fatalf("item1 = %+v", items[1])
	}
	if items[1].Detail != "github" {
		t.Fatalf("detail = %+v", items[1])
	}
	if cliItemsFromJSON("not json") != nil {
		t.Fatal("non-json decoded")
	}
	if cliItemsFromJSON(`{"a":1}`) != nil {
		t.Fatal("nameless objects decoded")
	}
}

func TestCliNameRe(t *testing.T) {
	for _, ok := range []string{"zread", "github-mcp-server", "owner/repo", "a.b_c:d@e"} {
		if !cliNameRe.MatchString(ok) {
			t.Fatalf("valid name rejected: %q", ok)
		}
	}
	for _, bad := range []string{"", "-x", "a b", "a;b", "--flag", "x\ny", strings.Repeat("a", 200)} {
		if cliNameRe.MatchString(bad) {
			t.Fatalf("bad name accepted: %q", bad)
		}
	}
}

func TestLoopbackOAuthURL(t *testing.T) {
	for _, tt := range []struct {
		raw string
		ok  bool
	}{
		{"http://127.0.0.1:8399/callback?code=x", true},
		{"http://localhost:1/cb", true},
		{"http://[::1]:80/cb", true},
		{"https://127.0.0.1/cb", true},
		{"http://192.168.1.5/cb", false},
		{"http://example.com/cb", false},
		{"file:///etc/passwd", false},
		{"ftp://127.0.0.1/x", false},
	} {
		u, err := url.Parse(tt.raw)
		if err != nil {
			t.Fatalf("parse %q: %v", tt.raw, err)
		}
		if got := loopbackOAuthURL(u); got != tt.ok {
			t.Fatalf("loopbackOAuthURL(%q) = %v, want %v", tt.raw, got, tt.ok)
		}
	}
}

// The toggle maps {name,enabled:false} onto `devin mcp disable <name>` —
// echoed args make the exact CLI invocation verifiable.
func TestMcpToggleRunsDisable(t *testing.T) {
	bin := buildStubCLI(t, `package main
import ("fmt";"os")
func main(){ fmt.Println("ARGS:", os.Args[1:]) }`)
	oldDevin, oldToken := *devinBin, *token
	defer func() { *devinBin, *token = oldDevin, oldToken }()
	*devinBin, *token = bin, ""

	srv := httptest.NewServer(http.HandlerFunc(mcpToggleHandler))
	defer srv.Close()

	st, m := mcpPost(t, srv, "/mcp/toggle", `{"name":"zread","enabled":false}`)
	if st != http.StatusOK {
		t.Fatalf("status = %d, body %v", st, m)
	}
	out, _ := m["output"].(string)
	if !strings.Contains(out, "mcp disable zread") {
		t.Fatalf("output = %q", out)
	}
	st, _ = mcpPost(t, srv, "/mcp/toggle", `{"name":"bad;name","enabled":true}`)
	if st != http.StatusBadRequest {
		t.Fatalf("bad name status = %d", st)
	}
}

// A non-loopback callback URL must never be fetched bridge-side.
func TestMcpAuthCallbackRejectsRemote(t *testing.T) {
	oldToken := *token
	defer func() { *token = oldToken }()
	*token = ""
	srv := httptest.NewServer(http.HandlerFunc(mcpAuthCallbackHandler))
	defer srv.Close()
	st, _ := mcpPost(t, srv, "/mcp/authCallback", `{"name":"x","url":"http://192.168.1.9/cb"}`)
	if st != http.StatusBadRequest {
		t.Fatalf("status = %d", st)
	}
}
