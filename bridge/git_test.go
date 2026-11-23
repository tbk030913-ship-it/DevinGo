// /git/diff tests: the endpoint returns a session workspace's uncommitted
// changes — `git diff HEAD -- .` plus synthesized all-add sections for
// untracked files — token-gated, workspace resolved through the hub's
// recorded cwd, same auth/origin gates as /fs/write (N6.1).
package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// gitRun runs git inside dir and fails the test on error.
func gitRun(t *testing.T, dir string, args ...string) {
	t.Helper()
	cmd := exec.Command("git", args...)
	cmd.Dir = dir
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("git %s: %v\n%s", strings.Join(args, " "), err, out)
	}
}

// gitDiffPost posts the JSON body to /git/diff and returns (status, body).
func gitDiffPost(t *testing.T, srv *httptest.Server, token, body string) (int, rpcMsg) {
	t.Helper()
	req, err := http.NewRequest(http.MethodPost, srv.URL+"/git/diff",
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
		t.Fatalf("post /git/diff: %v", err)
	}
	defer resp.Body.Close()
	var m rpcMsg
	_ = json.NewDecoder(resp.Body).Decode(&m)
	return resp.StatusCode, m
}

// plantSessionCwd gives the hub a session whose recorded workspace is dir —
// the state sessionGitDiff needs, without a live devin process.
func plantSessionCwd(t *testing.T, hub *Hub, sid, dir string) {
	t.Helper()
	hub.mu.Lock()
	defer hub.mu.Unlock()
	s := hub.getSessionLocked(sid)
	s.cwd = dir
}

func TestGitDiffEndpoint(t *testing.T) {
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
	if err := os.WriteFile(filepath.Join(repo, "a.txt"),
		[]byte("one\ntwo\n"), 0o644); err != nil {
		t.Fatalf("modify file: %v", err)
	}

	oldToken, oldHub := *token, bridgeHub
	defer func() { *token, bridgeHub = oldToken, oldHub }()
	*token = ""
	bridgeHub = newHub()
	plantSessionCwd(t, bridgeHub, "SID", repo)

	srv := httptest.NewServer(http.HandlerFunc(gitDiffHandler))
	defer srv.Close()

	// Happy path — the patch carries the unstaged change.
	st, m := gitDiffPost(t, srv, "", `{"sessionId":"SID"}`)
	if st != http.StatusOK {
		t.Fatalf("status = %d, body %v", st, m)
	}
	patch, _ := m["patch"].(string)
	if !strings.Contains(patch, "+two") || !strings.Contains(patch, "a.txt") {
		t.Fatalf("patch missing the workspace edit:\n%s", patch)
	}
	if m["truncated"] != false {
		t.Fatalf("truncated = %v", m["truncated"])
	}

	// Staged edits ride the same `git diff HEAD` payload.
	gitRun(t, repo, "add", "a.txt")
	st, m = gitDiffPost(t, srv, "", `{"sessionId":"SID"}`)
	if st != http.StatusOK || !strings.Contains(m["patch"].(string), "+two") {
		t.Fatalf("staged diff: status %d, patch %v", st, m["patch"])
	}

	// Unknown session → 404.
	st, _ = gitDiffPost(t, srv, "", `{"sessionId":"NOPE"}`)
	if st != http.StatusNotFound {
		t.Fatalf("unknown session status = %d", st)
	}

	// Missing sessionId → 400; wrong method → 405.
	st, _ = gitDiffPost(t, srv, "", `{}`)
	if st != http.StatusBadRequest {
		t.Fatalf("empty sessionId status = %d", st)
	}
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/git/diff", nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("get /git/diff: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusMethodNotAllowed {
		t.Fatalf("GET status = %d", resp.StatusCode)
	}

	// authorized() short-circuits for loopback peers, so the token gate
	// can't be exercised through httptest — covered by /fs/write's tests.
}

// Untracked files never appear in `git diff` — the endpoint synthesizes
// all-add sections for them — and a session rooted in a subdirectory is
// confined to that subtree (paths print cwd-relative).
func TestGitDiffUntrackedAndSubdir(t *testing.T) {
	repo := t.TempDir()
	gitRun(t, repo, "init")
	gitRun(t, repo, "config", "user.email", "t@t")
	gitRun(t, repo, "config", "user.name", "t")
	// A root-level edit OUTSIDE the session's subtree must not leak in.
	if err := os.WriteFile(filepath.Join(repo, "root.txt"),
		[]byte("r1\n"), 0o644); err != nil {
		t.Fatalf("seed root: %v", err)
	}
	gitRun(t, repo, "add", "root.txt")
	gitRun(t, repo, "commit", "-m", "init")
	if err := os.WriteFile(filepath.Join(repo, "root.txt"),
		[]byte("r1\nr2\n"), 0o644); err != nil {
		t.Fatalf("edit root: %v", err)
	}
	// Session workspace = repo/sub: one tracked edit + one brand-new file.
	sub := filepath.Join(repo, "sub")
	if err := os.MkdirAll(sub, 0o755); err != nil {
		t.Fatalf("mkdir sub: %v", err)
	}
	if err := os.WriteFile(filepath.Join(sub, "in.txt"),
		[]byte("a\n"), 0o644); err != nil {
		t.Fatalf("seed sub: %v", err)
	}
	gitRun(t, repo, "add", "sub/in.txt")
	gitRun(t, repo, "commit", "-m", "add sub")
	if err := os.WriteFile(filepath.Join(sub, "in.txt"),
		[]byte("a\nb\n"), 0o644); err != nil {
		t.Fatalf("edit sub: %v", err)
	}
	if err := os.WriteFile(filepath.Join(sub, "new.txt"),
		[]byte("fresh\nfile\n"), 0o644); err != nil {
		t.Fatalf("untracked: %v", err)
	}

	oldToken, oldHub := *token, bridgeHub
	defer func() { *token, bridgeHub = oldToken, oldHub }()
	*token = ""
	bridgeHub = newHub()
	plantSessionCwd(t, bridgeHub, "SID", sub)

	srv := httptest.NewServer(http.HandlerFunc(gitDiffHandler))
	defer srv.Close()

	st, m := gitDiffPost(t, srv, "", `{"sessionId":"SID"}`)
	if st != http.StatusOK {
		t.Fatalf("status = %d, body %v", st, m)
	}
	patch, _ := m["patch"].(string)
	// Inside: the tracked edit with a cwd-relative (not repo-root) path.
	if !strings.Contains(patch, "+++ b/in.txt") || !strings.Contains(patch, "+b") {
		t.Fatalf("missing tracked subdir edit:\n%s", patch)
	}
	// Inside: the untracked file as a synthesized all-add section.
	if !strings.Contains(patch, "diff --git a/new.txt b/new.txt") ||
		!strings.Contains(patch, "+fresh") {
		t.Fatalf("missing untracked file section:\n%s", patch)
	}
	// Outside: the repo-root edit stays out entirely.
	if strings.Contains(patch, "root.txt") {
		t.Fatalf("patch escaped the session subtree:\n%s", patch)
	}
}

// An unborn HEAD still reports the staged tree via `git diff --cached`.
func TestGitDiffUnbornHead(t *testing.T) {
	repo := t.TempDir()
	gitRun(t, repo, "init")
	gitRun(t, repo, "config", "user.email", "t@t")
	gitRun(t, repo, "config", "user.name", "t")
	if err := os.WriteFile(filepath.Join(repo, "x.txt"),
		[]byte("x1\n"), 0o644); err != nil {
		t.Fatalf("seed: %v", err)
	}
	gitRun(t, repo, "add", "x.txt") // staged, never committed

	oldToken, oldHub := *token, bridgeHub
	defer func() { *token, bridgeHub = oldToken, oldHub }()
	*token = ""
	bridgeHub = newHub()
	plantSessionCwd(t, bridgeHub, "SID", repo)

	srv := httptest.NewServer(http.HandlerFunc(gitDiffHandler))
	defer srv.Close()

	st, m := gitDiffPost(t, srv, "", `{"sessionId":"SID"}`)
	if st != http.StatusOK {
		t.Fatalf("status = %d, body %v", st, m)
	}
	patch, _ := m["patch"].(string)
	if !strings.Contains(patch, "+++ b/x.txt") || !strings.Contains(patch, "+x1") {
		t.Fatalf("staged file on unborn HEAD missing:\n%s", patch)
	}
}
