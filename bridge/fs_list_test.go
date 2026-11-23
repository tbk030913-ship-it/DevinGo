// /fs/list tests: the endpoint lists a session workspace's files for the
// composer's @file picker — tracked plus non-ignored untracked entries,
// cwd-relative, subtree-confined, token-gated, same auth/origin gates as
// /fs/write and /git/diff (N6.2).
package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// fsListPost posts the JSON body to /fs/list and returns (status, body).
func fsListPost(t *testing.T, srv *httptest.Server, token, body string) (int, rpcMsg) {
	t.Helper()
	req, err := http.NewRequest(http.MethodPost, srv.URL+"/fs/list",
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
		t.Fatalf("post /fs/list: %v", err)
	}
	defer resp.Body.Close()
	var m rpcMsg
	_ = json.NewDecoder(resp.Body).Decode(&m)
	return resp.StatusCode, m
}

func fsListFiles(t *testing.T, m rpcMsg) []string {
	t.Helper()
	raw, ok := m["files"].([]any)
	if !ok {
		t.Fatalf("files missing or not an array: %v", m)
	}
	out := make([]string, 0, len(raw))
	for _, v := range raw {
		s, _ := v.(string)
		out = append(out, s)
	}
	return out
}

// Tracked and non-ignored untracked files are listed cwd-relative; a
// session rooted in a subdirectory only sees its own subtree.
func TestFsListEndpoint(t *testing.T) {
	repo := t.TempDir()
	gitRun(t, repo, "init")
	gitRun(t, repo, "config", "user.email", "t@t")
	gitRun(t, repo, "config", "user.name", "t")
	if err := os.WriteFile(filepath.Join(repo, "a.txt"),
		[]byte("one\n"), 0o644); err != nil {
		t.Fatalf("seed file: %v", err)
	}
	gitRun(t, repo, "add", "a.txt")
	gitRun(t, repo, "commit", "-m", "init")
	// Untracked-but-listable and ignored-but-hidden fixtures.
	if err := os.WriteFile(filepath.Join(repo, "new.txt"),
		[]byte("n\n"), 0o644); err != nil {
		t.Fatalf("untracked: %v", err)
	}
	if err := os.WriteFile(filepath.Join(repo, ".gitignore"),
		[]byte("ignored.txt\n"), 0o644); err != nil {
		t.Fatalf("gitignore: %v", err)
	}
	if err := os.WriteFile(filepath.Join(repo, "ignored.txt"),
		[]byte("i\n"), 0o644); err != nil {
		t.Fatalf("ignored: %v", err)
	}

	oldToken, oldHub := *token, bridgeHub
	defer func() { *token, bridgeHub = oldToken, oldHub }()
	*token = ""
	bridgeHub = newHub()
	plantSessionCwd(t, bridgeHub, "SID", repo)

	srv := httptest.NewServer(http.HandlerFunc(fsListHandler))
	defer srv.Close()

	st, m := fsListPost(t, srv, "", `{"sessionId":"SID"}`)
	if st != http.StatusOK {
		t.Fatalf("status = %d, body %v", st, m)
	}
	files := fsListFiles(t, m)
	joined := "\x00" + strings.Join(files, "\x00") + "\x00"
	for _, want := range []string{"a.txt", "new.txt", ".gitignore"} {
		if !strings.Contains(joined, "\x00"+want+"\x00") {
			t.Fatalf("files missing %q: %v", want, files)
		}
	}
	if strings.Contains(joined, "\x00ignored.txt\x00") {
		t.Fatalf("gitignored file listed: %v", files)
	}
	if m["truncated"] != false {
		t.Fatalf("truncated = %v", m["truncated"])
	}

	// A session rooted in a subdirectory sees only its own subtree —
	// cwd-relative, so a.txt and root files stay out.
	sub := filepath.Join(repo, "sub")
	if err := os.MkdirAll(sub, 0o755); err != nil {
		t.Fatalf("mkdir sub: %v", err)
	}
	if err := os.WriteFile(filepath.Join(sub, "in.txt"),
		[]byte("x\n"), 0o644); err != nil {
		t.Fatalf("seed sub: %v", err)
	}
	plantSessionCwd(t, bridgeHub, "SUB", sub)
	st, m = fsListPost(t, srv, "", `{"sessionId":"SUB"}`)
	if st != http.StatusOK {
		t.Fatalf("subdir status = %d, body %v", st, m)
	}
	files = fsListFiles(t, m)
	if len(files) != 1 || files[0] != "in.txt" {
		t.Fatalf("subdir files = %v, want [in.txt]", files)
	}

	// Unknown session → 404; missing sessionId → 400; GET → 405.
	st, _ = fsListPost(t, srv, "", `{"sessionId":"NOPE"}`)
	if st != http.StatusNotFound {
		t.Fatalf("unknown session status = %d", st)
	}
	st, _ = fsListPost(t, srv, "", `{}`)
	if st != http.StatusBadRequest {
		t.Fatalf("empty sessionId status = %d", st)
	}
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/fs/list", nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("get /fs/list: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusMethodNotAllowed {
		t.Fatalf("GET status = %d", resp.StatusCode)
	}
}
