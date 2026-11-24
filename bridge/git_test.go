// /git/diff tests: the endpoint returns a session workspace's uncommitted
// changes — `git diff HEAD -- .` plus synthesized all-add sections for
// untracked files — token-gated, workspace resolved through the hub's
// recorded cwd, same auth/origin gates as /fs/write (N6.1).
package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
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
	s := hub.getSessionLocked(sid, "")
	s.cwd = dir
}

func TestGitDiffEndpoint(t *testing.T) {
	repo := t.TempDir()
	gitRun(t, repo, "init", "-b", "main")
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
	// GET exists now (W8.2's query mode) — without sessionId/cwd it's a 400.
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/git/diff", nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("get /git/diff: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
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
	gitRun(t, repo, "init", "-b", "main")
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
	gitRun(t, repo, "init", "-b", "main")
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

// ------------------------------------------------------- W8.2 git ops ---

// gitPostJSON posts a raw JSON body to the test server's bound handler.
func gitPostJSON(t *testing.T, srv *httptest.Server, body string) (int, rpcMsg) {
	t.Helper()
	req, err := http.NewRequest(http.MethodPost, srv.URL,
		strings.NewReader(body))
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("post: %v", err)
	}
	defer resp.Body.Close()
	var m rpcMsg
	_ = json.NewDecoder(resp.Body).Decode(&m)
	return resp.StatusCode, m
}

// gitOut returns trimmed stdout of a git call in dir.
func gitOut(t *testing.T, dir string, args ...string) string {
	t.Helper()
	cmd := exec.Command("git", args...)
	cmd.Dir = dir
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("git %s: %v\n%s", strings.Join(args, " "), err, out)
	}
	return strings.TrimSpace(string(out))
}

// gitSeedRepo makes a repo with one committed a.txt and returns its dir.
func gitSeedRepo(t *testing.T) string {
	t.Helper()
	repo := t.TempDir()
	gitRun(t, repo, "init", "-b", "main")
	gitRun(t, repo, "config", "user.email", "t@t")
	gitRun(t, repo, "config", "user.name", "t")
	if err := os.WriteFile(filepath.Join(repo, "a.txt"),
		[]byte("one\n"), 0o644); err != nil {
		t.Fatalf("seed: %v", err)
	}
	gitRun(t, repo, "add", "a.txt")
	gitRun(t, repo, "commit", "-m", "init")
	return repo
}

// swapGitHub installs a fresh hub + no token for one test's handlers.
func swapGitHub(t *testing.T, sid, dir string) {
	t.Helper()
	oldToken, oldHub := *token, bridgeHub
	t.Cleanup(func() { *token, bridgeHub = oldToken, oldHub })
	*token = ""
	bridgeHub = newHub()
	plantSessionCwd(t, bridgeHub, sid, dir)
}

// /git/status: branch + dirty files merge porcelain rows with numstat
// counts; untracked files count their own lines; a non-repo answers
// isRepo:false instead of an error.
func TestGitStatusEndpoint(t *testing.T) {
	repo := gitSeedRepo(t)
	if err := os.WriteFile(filepath.Join(repo, "a.txt"),
		[]byte("one\ntwo\nthree\n"), 0o644); err != nil {
		t.Fatalf("modify: %v", err)
	}
	if err := os.WriteFile(filepath.Join(repo, "new.txt"),
		[]byte("n1\nn2\n"), 0o644); err != nil {
		t.Fatalf("untracked: %v", err)
	}
	nonRepo := t.TempDir()

	swapGitHub(t, "SID", repo)
	srv := httptest.NewServer(http.HandlerFunc(gitStatusHandler))
	defer srv.Close()

	st, m := fsGet(t, srv, "/git/status", "",
		url.Values{"sessionId": {"SID"}})
	if st != http.StatusOK {
		t.Fatalf("status = %d, body %v", st, m)
	}
	if m["isRepo"] != true {
		t.Fatalf("isRepo = %v", m["isRepo"])
	}
	branch := gitOut(t, repo, "symbolic-ref", "--short", "HEAD")
	if m["branch"] != branch {
		t.Fatalf("branch = %v, want %q", m["branch"], branch)
	}
	if m["isDefaultBranch"] != true {
		// The seed's first branch is whatever `git init` picked — it is
		// by definition the default the status endpoint reports.
		t.Fatalf("isDefaultBranch = %v on %q", m["isDefaultBranch"], branch)
	}
	if m["ahead"] != float64(0) || m["behind"] != float64(0) {
		t.Fatalf("ahead/behind = %v/%v, want 0/0", m["ahead"], m["behind"])
	}
	files, ok := m["files"].([]any)
	if !ok || len(files) != 2 {
		t.Fatalf("files = %v", m["files"])
	}
	byPath := map[string]rpcMsg{}
	for _, f := range files {
		if o, ok := f.(map[string]any); ok {
			byPath[o["path"].(string)] = o
		}
	}
	if byPath["a.txt"] == nil || byPath["a.txt"]["status"] != "M" {
		t.Fatalf("a.txt row = %v", byPath["a.txt"])
	}
	if ins, _ := byPath["a.txt"]["insertions"].(float64); ins != 2 {
		t.Fatalf("a.txt insertions = %v, want 2", byPath["a.txt"]["insertions"])
	}
	if byPath["new.txt"] == nil || byPath["new.txt"]["status"] != "?" {
		t.Fatalf("new.txt row = %v", byPath["new.txt"])
	}
	if ins, _ := byPath["new.txt"]["insertions"].(float64); ins != 2 {
		t.Fatalf("new.txt insertions = %v, want 2", byPath["new.txt"]["insertions"])
	}

	// A workspace that isn't a repo → isRepo:false, still 200.
	swapGitHub(t, "PLAIN", nonRepo)
	st, m = fsGet(t, srv, "/git/status", "",
		url.Values{"sessionId": {"PLAIN"}})
	if st != http.StatusOK || m["isRepo"] != false {
		t.Fatalf("non-repo status = %d, %v", st, m)
	}
	// Root resolution failures share the fs sentinels.
	st, _ = fsGet(t, srv, "/git/status", "", url.Values{})
	if st != http.StatusBadRequest {
		t.Fatalf("no-session status = %d, want 400", st)
	}
}

// /git/branches + /git/checkout: create a branch, see it listed as
// current, switch back; bad ref names never reach the command line.
func TestGitBranchesAndCheckout(t *testing.T) {
	repo := gitSeedRepo(t)
	defaultBranch := gitOut(t, repo, "symbolic-ref", "--short", "HEAD")

	swapGitHub(t, "SID", repo)
	branchesSrv := httptest.NewServer(http.HandlerFunc(gitBranchesHandler))
	defer branchesSrv.Close()
	coSrv := httptest.NewServer(http.HandlerFunc(gitCheckoutHandler))
	defer coSrv.Close()

	// checkout -b via {createBranch} → {ok, branch}.
	st, m := gitPostJSON(t, coSrv, `{"sessionId":"SID","createBranch":"feat-x"}`)
	if st != http.StatusOK || m["branch"] != "feat-x" {
		t.Fatalf("create checkout = %d, %v", st, m)
	}
	if cur := gitOut(t, repo, "symbolic-ref", "--short", "HEAD"); cur != "feat-x" {
		t.Fatalf("worktree branch = %q, want feat-x", cur)
	}

	// Branch list shows both, marks current + default.
	st, m = fsGet(t, branchesSrv, "/git/branches", "",
		url.Values{"sessionId": {"SID"}})
	if st != http.StatusOK {
		t.Fatalf("branches status = %d, %v", st, m)
	}
	if m["current"] != "feat-x" || m["defaultBranch"] != defaultBranch {
		t.Fatalf("current/default = %v/%v", m["current"], m["defaultBranch"])
	}
	names, _ := m["branches"].([]any)
	if len(names) != 2 {
		t.Fatalf("branches = %v", m["branches"])
	}

	// {branch} switches back.
	st, m = gitPostJSON(t, coSrv,
		`{"sessionId":"SID","branch":"`+defaultBranch+`"}`)
	if st != http.StatusOK || m["branch"] != defaultBranch {
		t.Fatalf("switch back = %d, %v", st, m)
	}

	// Both/neither selector → 400.
	for _, body := range []string{
		`{"sessionId":"SID"}`,
		`{"sessionId":"SID","branch":"a","createBranch":"b"}`,
	} {
		if st, _ = gitPostJSON(t, coSrv, body); st != http.StatusBadRequest {
			t.Fatalf("body %s status = %d, want 400", body, st)
		}
	}
	// Ref names check-ref-format rejects → 400, nothing created.
	for _, bad := range []string{"bad..name", "-x", "a b", "a\tb", ".lock", ""} {
		body := `{"sessionId":"SID","createBranch":"` + bad + `"}`
		if st, _ := gitPostJSON(t, coSrv, body); st != http.StatusBadRequest {
			t.Fatalf("createBranch %q status = %d, want 400", bad, st)
		}
	}
	// A well-formed but nonexistent branch → git's own failure, sanitized.
	st, m = gitPostJSON(t, coSrv, `{"sessionId":"SID","branch":"no-such"}`)
	if st != http.StatusInternalServerError || m["error"] == nil {
		t.Fatalf("missing branch = %d, %v", st, m)
	}
	if strings.Contains(m["error"].(string), repo) {
		t.Fatalf("error leaked the workspace path: %v", m["error"])
	}
}

// /git/commit: selected files stage through `add --` then commit;
// createBranch first lands a `switch -c`; path/massage validation is a
// 400 before any git call runs.
func TestGitCommitEndpoint(t *testing.T) {
	repo := gitSeedRepo(t)
	if err := os.WriteFile(filepath.Join(repo, "a.txt"),
		[]byte("one\ntwo\n"), 0o644); err != nil {
		t.Fatalf("modify: %v", err)
	}
	if err := os.WriteFile(filepath.Join(repo, "b.txt"),
		[]byte("b1\n"), 0o644); err != nil {
		t.Fatalf("untracked: %v", err)
	}

	swapGitHub(t, "SID", repo)
	srv := httptest.NewServer(http.HandlerFunc(gitCommitHandler))
	defer srv.Close()

	// Commit just one of the two dirty files.
	st, m := gitPostJSON(t, srv,
		`{"sessionId":"SID","files":["a.txt"],"message":"test: a change"}`)
	if st != http.StatusOK || m["ok"] != true {
		t.Fatalf("commit = %d, %v", st, m)
	}
	if m["commit"] == nil || m["commit"] == "" {
		t.Fatalf("commit sha missing: %v", m)
	}
	if subj := gitOut(t, repo, "log", "-1", "--format=%s"); subj != "test: a change" {
		t.Fatalf("commit subject = %q", subj)
	}
	// b.txt stays untracked; a.txt's stage+commit left it clean.
	st, m = gitPostJSON(t, srv,
		`{"sessionId":"SID","files":["b.txt"],"message":"feat: b","createBranch":"topic"}`)
	if st != http.StatusOK || m["branch"] != "topic" {
		t.Fatalf("commit on new branch = %d, %v", st, m)
	}
	if cur := gitOut(t, repo, "symbolic-ref", "--short", "HEAD"); cur != "topic" {
		t.Fatalf("worktree branch = %q, want topic", cur)
	}

	// Validation: empty files/message, bad ref, traversal — all 400.
	for _, body := range []string{
		`{"sessionId":"SID","files":[],"message":"m"}`,
		`{"sessionId":"SID","files":["a.txt"],"message":""}`,
		`{"sessionId":"SID","files":["a.txt"],"message":"m","createBranch":"bad..x"}`,
		`{"sessionId":"SID","files":["../out.txt"],"message":"m"}`,
		`{"sessionId":"SID","files":["/abs.txt"],"message":"m"}`,
		`{"sessionId":"SID","files":["C:/x.txt"],"message":"m"}`,
		`{"sessionId":"SID","files":["."],"message":"m"}`,
	} {
		if st, m := gitPostJSON(t, srv, body); st != http.StatusBadRequest {
			t.Fatalf("body %s status = %d (%v), want 400", body, st, m)
		}
	}
	// A selection that stages nothing → git's "nothing to commit" error.
	st, m = gitPostJSON(t, srv,
		`{"sessionId":"SID","files":["a.txt"],"message":"again"}`)
	if st != http.StatusInternalServerError || m["error"] == nil {
		t.Fatalf("empty commit = %d, %v", st, m)
	}
}

// GET /git/diff?mode= — the query form: uncommitted (default) matches the
// POST shape, path confines to one file, mode=branch diffs the default-
// branch merge-base against the worktree.
func TestGitDiffGetModes(t *testing.T) {
	repo := gitSeedRepo(t)
	if err := os.WriteFile(filepath.Join(repo, "a.txt"),
		[]byte("one\ntwo\n"), 0o644); err != nil {
		t.Fatalf("modify: %v", err)
	}
	if err := os.WriteFile(filepath.Join(repo, "b.txt"),
		[]byte("b1\n"), 0o644); err != nil {
		t.Fatalf("untracked: %v", err)
	}

	swapGitHub(t, "SID", repo)
	srv := httptest.NewServer(http.HandlerFunc(gitDiffHandler))
	defer srv.Close()

	// Default mode: the uncommitted patch, a.txt + synthesized b.txt.
	st, m := fsGet(t, srv, "/git/diff", "",
		url.Values{"sessionId": {"SID"}})
	if st != http.StatusOK {
		t.Fatalf("status = %d, %v", st, m)
	}
	patch, _ := m["patch"].(string)
	if !strings.Contains(patch, "+two") || !strings.Contains(patch, "b/b.txt") {
		t.Fatalf("default-mode patch:\n%s", patch)
	}
	// path= confines to one file — b.txt (and a.txt's edit) stay out.
	st, m = fsGet(t, srv, "/git/diff", "",
		url.Values{"sessionId": {"SID"}, "path": {"a.txt"}})
	if st != http.StatusOK {
		t.Fatalf("path status = %d, %v", st, m)
	}
	patch, _ = m["patch"].(string)
	if !strings.Contains(patch, "a.txt") || strings.Contains(patch, "b.txt") {
		t.Fatalf("path-filtered patch:\n%s", patch)
	}
	for _, bad := range []string{"../x", "a/../../x", "/abs", "C:/x", "-p"} {
		st, _ = fsGet(t, srv, "/git/diff", "",
			url.Values{"sessionId": {"SID"}, "path": {bad}})
		if st != http.StatusBadRequest {
			t.Fatalf("path %q status = %d, want 400", bad, st)
		}
	}

	// mode=branch: commit the worktree change on a feature branch — the
	// patch is the merge-base diff, not the uncommitted one.
	gitRun(t, repo, "checkout", "-b", "feat")
	gitRun(t, repo, "add", "a.txt", "b.txt")
	gitRun(t, repo, "commit", "-m", "feat work")
	st, m = fsGet(t, srv, "/git/diff", "",
		url.Values{"sessionId": {"SID"}, "mode": {"branch"}})
	if st != http.StatusOK {
		t.Fatalf("branch status = %d, %v", st, m)
	}
	patch, _ = m["patch"].(string)
	if !strings.Contains(patch, "+two") || !strings.Contains(patch, "+b1") {
		t.Fatalf("branch-mode patch missing committed work:\n%s", patch)
	}
	// Unknown mode → 400.
	st, _ = fsGet(t, srv, "/git/diff", "",
		url.Values{"sessionId": {"SID"}, "mode": {"wat"}})
	if st != http.StatusBadRequest {
		t.Fatalf("bad mode status = %d, want 400", st)
	}
}

// /git/push + /git/pull exercise the shared {sessionId|cwd} shape: a
// repo with no remote fails with git's own sanitized error, a non-repo
// workspace is a 400.
func TestGitPushPullEndpoints(t *testing.T) {
	repo := gitSeedRepo(t)
	nonRepo := t.TempDir()

	swapGitHub(t, "SID", repo)
	plantSessionCwd(t, bridgeHub, "PLAIN", nonRepo)

	for _, ep := range []struct {
		name    string
		handler http.HandlerFunc
	}{
		{"push", gitPushHandler},
		{"pull", gitPullHandler},
	} {
		srv := httptest.NewServer(http.HandlerFunc(ep.handler))
		defer srv.Close()
		// No remote configured → git's own (sanitized) failure text.
		st, m := gitPostJSON(t, srv, `{"sessionId":"SID"}`)
		if st != http.StatusInternalServerError || m["error"] == nil {
			t.Fatalf("%s no-remote = %d, %v", ep.name, st, m)
		}
		if strings.Contains(m["error"].(string), repo) {
			t.Fatalf("%s error leaked the workspace path: %v", ep.name, m["error"])
		}
		// Non-repo workspace → 400 not-a-repo.
		st, m = gitPostJSON(t, srv, `{"sessionId":"PLAIN"}`)
		if st != http.StatusBadRequest {
			t.Fatalf("%s non-repo = %d, %v", ep.name, st, m)
		}
	}
}

// cleanGitPath / porcelainLetter unit checks — the validation gate the
// mutation endpoints share.
func TestGitPathAndPorcelain(t *testing.T) {
	for _, tc := range []struct {
		in   string
		want string
		ok   bool
	}{
		{"", ".", true},
		{".", ".", true},
		{"a/b.kt", "a/b.kt", true},
		{"a b/c.kt", "a b/c.kt", true},
		{"../x", "", false},
		{"a/../x", "", false},
		{"/abs", "", false},
		{"C:/x", "", false},
		{"-p", "", false},
		// Glob metachars: `--` stops option parsing, not pathspec
		// fnmatch — a literal a*b name would sweep siblings (W8.2 review).
		{"a*b", "", false},
		{"x?[y]", "", false},
	} {
		got, ok := cleanGitPath(tc.in)
		if ok != tc.ok || (ok && got != tc.want) {
			t.Fatalf("cleanGitPath(%q) = %q,%v want %q,%v",
				tc.in, got, ok, tc.want, tc.ok)
		}
	}
	for xy, want := range map[string]string{
		" M": "M", "M ": "M", "MM": "M", "A ": "A",
		"??": "?", "R ": "R", "UU": "U", "AA": "U", " D": "D",
	} {
		if got := porcelainLetter(xy); got != want {
			t.Fatalf("porcelainLetter(%q) = %q, want %q", xy, got, want)
		}
	}
}

// A file staged OUTSIDE the selection must not ride into the commit —
// `commit -m` alone would sweep the whole index (W8.2 quality review I1).
func TestGitCommitLeavesUnselectedStaged(t *testing.T) {
	repo := gitSeedRepo(t)
	// Stage c.txt, modify a.txt unstaged — the index holds work the
	// user did NOT select.
	if err := os.WriteFile(filepath.Join(repo, "c.txt"),
		[]byte("staged-elsewhere\n"), 0o644); err != nil {
		t.Fatalf("seed c: %v", err)
	}
	gitRun(t, repo, "add", "c.txt")
	if err := os.WriteFile(filepath.Join(repo, "a.txt"),
		[]byte("one\ntwo\n"), 0o644); err != nil {
		t.Fatalf("modify a: %v", err)
	}

	swapGitHub(t, "SID", repo)
	srv := httptest.NewServer(http.HandlerFunc(gitCommitHandler))
	defer srv.Close()
	st, m := gitPostJSON(t, srv,
		`{"sessionId":"SID","files":["a.txt"],"message":"test: only a"}`)
	if st != http.StatusOK || m["ok"] != true {
		t.Fatalf("commit = %d, %v", st, m)
	}
	// HEAD contains a.txt's edit but NOT c.txt; c.txt remains staged.
	if names := gitOut(t, repo, "diff-tree", "--no-commit-id",
		"--name-only", "-r", "HEAD"); names != "a.txt" {
		t.Fatalf("committed files = %q, want only a.txt", names)
	}
	staged := gitOut(t, repo, "diff", "--cached", "--name-only")
	if staged != "c.txt" {
		t.Fatalf("staged = %q, want c.txt untouched", staged)
	}
}

// Validation happens before `switch -c`: a bad file in the request must
// not leave the worktree sitting on the half-created branch.
func TestGitCommitBadFileNoBranchSwitch(t *testing.T) {
	repo := gitSeedRepo(t)
	if err := os.WriteFile(filepath.Join(repo, "a.txt"),
		[]byte("one\ntwo\n"), 0o644); err != nil {
		t.Fatalf("modify: %v", err)
	}
	swapGitHub(t, "SID", repo)
	srv := httptest.NewServer(http.HandlerFunc(gitCommitHandler))
	defer srv.Close()
	st, _ := gitPostJSON(t, srv,
		`{"sessionId":"SID","files":["../x"],"message":"m","createBranch":"oops"}`)
	if st != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", st)
	}
	if cur := gitOut(t, repo, "symbolic-ref", "--short", "HEAD"); cur != "main" {
		t.Fatalf("branch after failed commit = %q, want main", cur)
	}
	if b := gitOut(t, repo, "branch", "--list", "oops"); b != "" {
		t.Fatalf("branch 'oops' exists after rejected commit")
	}
}

// First push on a fresh repo takes the `-u origin HEAD` path; a second
// clone's commit then pulls clean via --ff-only.
func TestGitPushPullHappyPath(t *testing.T) {
	repo := gitSeedRepo(t)
	bare := t.TempDir()
	gitRun(t, bare, "init", "--bare", "-b", "main")
	gitRun(t, repo, "remote", "add", "origin", bare)

	swapGitHub(t, "SID", repo)
	push := httptest.NewServer(http.HandlerFunc(gitPushHandler))
	defer push.Close()
	st, m := gitPostJSON(t, push, `{"sessionId":"SID"}`)
	if st != http.StatusOK || m["ok"] != true {
		t.Fatalf("push = %d, %v", st, m)
	}
	if ref := gitOut(t, bare, "rev-parse", "--verify", "main"); ref == "" {
		t.Fatalf("bare remote missing main after push")
	}
	if _, err := exec.Command("git", "-C", repo,
		"rev-parse", "--verify", "@{upstream}").Output(); err != nil {
		t.Fatalf("upstream not set after first push: %v", err)
	}

	// A second clone advances the remote; /git/pull fast-forwards repo.
	clone := t.TempDir()
	gitRun(t, clone, "clone", "-q", bare, ".")
	gitRun(t, clone, "config", "user.email", "t@t")
	gitRun(t, clone, "config", "user.name", "t")
	if err := os.WriteFile(filepath.Join(clone, "z.txt"),
		[]byte("z\n"), 0o644); err != nil {
		t.Fatalf("clone write: %v", err)
	}
	gitRun(t, clone, "add", "z.txt")
	gitRun(t, clone, "commit", "-q", "-m", "remote add")
	gitRun(t, clone, "push", "-q", "origin", "main")

	pull := httptest.NewServer(http.HandlerFunc(gitPullHandler))
	defer pull.Close()
	st, m = gitPostJSON(t, pull, `{"sessionId":"SID"}`)
	if st != http.StatusOK || m["ok"] != true {
		t.Fatalf("pull = %d, %v", st, m)
	}
	if _, err := os.Stat(filepath.Join(repo, "z.txt")); err != nil {
		t.Fatalf("z.txt missing after pull: %v", err)
	}
}
