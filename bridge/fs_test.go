// /fs/write tests: the endpoint writes a file inside a session's recorded
// workspace — token-gated, path-contained. The happy path needs a real
// session so the hub holds its cwd; the rejection paths don't.
package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// fsPost posts the JSON body and returns (status, decoded body).
func fsPost(t *testing.T, srv *httptest.Server, token, body string) (int, rpcMsg) {
	t.Helper()
	req, err := http.NewRequest(http.MethodPost, srv.URL+"/fs/write",
		strings.NewReader(body))
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("post /fs/write: %v", err)
	}
	defer resp.Body.Close()
	var m rpcMsg
	_ = json.NewDecoder(resp.Body).Decode(&m)
	return resp.StatusCode, m
}

// initAndNewCwd is initAndNew with a caller-chosen cwd (the workspace root
// /fs/write must resolve against).
func initAndNewCwd(t *testing.T, tc *testClient, cwd string) string {
	t.Helper()
	tc.request(t, 1, "initialize", rpcMsg{"protocolVersion": 1, "clientCapabilities": rpcMsg{}})
	if resp := tc.awaitID(t, 1, 20*time.Second); resp["error"] != nil {
		t.Fatalf("initialize: %v", resp["error"])
	}
	tc.request(t, 2, "session/new", rpcMsg{"cwd": cwd, "mcpServers": []any{}})
	resp := tc.awaitID(t, 2, 20*time.Second)
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

// The token guards /fs/write the same as /acp and /models: no bearer → 401,
// the right one → the handler (which then 404s the unknown session).
func TestFsWriteRequiresAuth(t *testing.T) {
	setupHub(t, fakeDevin(t))
	oldToken := *token
	*token = "test-secret"
	t.Cleanup(func() { *token = oldToken })
	srv := newTestServer(t)

	// httptest serves on 127.0.0.1 — the loopback exemption would let an
	// untokened request through, so spoof the Host to break it.
	status, _ := func() (int, rpcMsg) {
		req, _ := http.NewRequest(http.MethodPost, srv.URL+"/fs/write",
			strings.NewReader(`{"sessionId":"S001","path":"plan.md","content":"x"}`))
		req.Host = "evil.example"
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("post: %v", err)
		}
		defer resp.Body.Close()
		var m rpcMsg
		_ = json.NewDecoder(resp.Body).Decode(&m)
		return resp.StatusCode, m
	}()
	if status != http.StatusUnauthorized {
		t.Fatalf("unauthed /fs/write: status %d, want 401", status)
	}
	status, m := fsPost(t, srv, "test-secret",
		`{"sessionId":"NOPE","path":"plan.md","content":"x"}`)
	if status != http.StatusNotFound {
		t.Fatalf("authed unknown session: status %d (%v), want 404", status, m)
	}
}

// A valid nested relative path lands under the session's recorded cwd —
// parent directories are created and the content round-trips.
func TestFsWriteSessionCwd(t *testing.T) {
	setupHub(t, fakeDevin(t))
	srv := newTestServer(t)
	c := dialClient(t, srv)
	workspace := t.TempDir()
	sid := initAndNewCwd(t, c, filepath.ToSlash(workspace))

	status, m := fsPost(t, srv, "", `{"sessionId":"`+sid+
		`","path":"docs/plan.md","content":"# Plan\n"}`)
	if status != http.StatusOK {
		t.Fatalf("/fs/write: status %d (%v), want 200", status, m)
	}
	got, err := os.ReadFile(filepath.Join(workspace, "docs", "plan.md"))
	if err != nil {
		t.Fatalf("plan.md not written under the session cwd: %v", err)
	}
	if string(got) != "# Plan\n" {
		t.Fatalf("plan.md content = %q, want %q", got, "# Plan\n")
	}
}

// Traversal, absolute paths and prefix-confused siblings must never leave
// the workspace — including the classic /work vs /work2 bypass a naive
// HasPrefix check would allow.
func TestFsWriteRejectsEscapes(t *testing.T) {
	setupHub(t, fakeDevin(t))
	srv := newTestServer(t)
	c := dialClient(t, srv)
	root := t.TempDir()
	workspace := filepath.Join(root, "work")
	if err := os.MkdirAll(workspace, 0o755); err != nil {
		t.Fatalf("mkdir workspace: %v", err)
	}
	sibling := filepath.Join(root, "work2") // shares the "work" prefix
	sid := initAndNewCwd(t, c, filepath.ToSlash(workspace))

	bad := []string{
		`../plan.md`,
		`docs/../../plan.md`,
		`../work2/plan.md`,
		// Absolute (json.Marshal escapes it): rejected via IsAbs/VolumeName.
		filepath.Join(root, "abs.md"),
	}
	for _, p := range bad {
		body, _ := json.Marshal(rpcMsg{
			"sessionId": sid, "path": p, "content": "x",
		})
		status, m := fsPost(t, srv, "", string(body))
		if status != http.StatusForbidden {
			t.Fatalf("path %q: status %d (%v), want 403", p, status, m)
		}
	}
	if _, err := os.Stat(filepath.Join(sibling, "plan.md")); !os.IsNotExist(err) {
		t.Fatalf("prefix-confused write landed in the sibling dir: %v", err)
	}
	if _, err := os.Stat(filepath.Join(root, "plan.md")); !os.IsNotExist(err) {
		t.Fatalf("traversal write landed above the workspace: %v", err)
	}
}

// A symlink planted at the final path component must be replaced, never
// followed — otherwise /fs/write escapes the workspace through it.
func TestFsWriteReplacesTargetSymlink(t *testing.T) {
	setupHub(t, fakeDevin(t))
	srv := newTestServer(t)
	c := dialClient(t, srv)
	workspace := t.TempDir()
	outside := filepath.Join(t.TempDir(), "secret.txt")
	if err := os.WriteFile(outside, []byte("secret"), 0o644); err != nil {
		t.Fatalf("seed outside file: %v", err)
	}
	link := filepath.Join(workspace, "plan.md")
	if err := os.Symlink(outside, link); err != nil {
		t.Skipf("symlink unavailable (Windows dev mode?): %v", err)
	}
	sid := initAndNewCwd(t, c, filepath.ToSlash(workspace))

	status, m := fsPost(t, srv, "", `{"sessionId":"`+sid+
		`","path":"plan.md","content":"# Plan\n"}`)
	if status != http.StatusOK {
		t.Fatalf("/fs/write over symlink: status %d (%v), want 200", status, m)
	}
	if got, _ := os.ReadFile(outside); string(got) != "secret" {
		t.Fatalf("write followed the symlink — outside file = %q", got)
	}
	fi, err := os.Lstat(link)
	if err != nil || fi.Mode()&os.ModeSymlink != 0 {
		t.Fatalf("target still a symlink after write: %v %v", fi, err)
	}
	if got, _ := os.ReadFile(link); string(got) != "# Plan\n" {
		t.Fatalf("plan.md content = %q", got)
	}
}

// Malformed JSON, missing fields and an oversized body are all rejected
// before the session lookup happens.
func TestFsWriteBadRequests(t *testing.T) {
	setupHub(t, fakeDevin(t))
	srv := newTestServer(t)

	for name, body := range map[string]string{
		"malformed":   `{not json`,
		"missingSid":  `{"path":"plan.md","content":"x"}`,
		"missingPath": `{"sessionId":"S001","content":"x"}`,
	} {
		status, _ := fsPost(t, srv, "", body)
		if status != http.StatusBadRequest {
			t.Fatalf("%s: status %d, want 400", name, status)
		}
	}
	big := `{"sessionId":"S001","path":"p","content":"` +
		strings.Repeat("x", fsWriteMaxBody) + `"}`
	status, _ := fsPost(t, srv, "", big)
	if status != http.StatusBadRequest {
		t.Fatalf("oversized body: status %d, want 400", status)
	}
	// And a non-POST method doesn't reach the body parsing at all.
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/fs/write",
		bytes.NewReader(nil))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("get /fs/write: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusMethodNotAllowed {
		t.Fatalf("GET /fs/write: status %d, want 405", resp.StatusCode)
	}
}
