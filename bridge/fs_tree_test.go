// /fs/list dir mode + /fs/read tests (W8.1): GET /fs/list?path= lists ONE
// directory level ({name, relPath, kind, size, ignored}), GET /fs/read
// returns ≤1 MiB of a file ({content,truncated} or {binary,mime}). Roots
// resolve via sessionId — or an explicit ?cwd= proven inside a hosted
// workspace; traversal stays a strict filepath.Rel containment check.
package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// fsGet issues GET /fs/list (or /fs/read) with the given query params.
func fsGet(t *testing.T, srv *httptest.Server, endpoint, token string, q url.Values) (int, rpcMsg) {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet,
		srv.URL+endpoint+"?"+q.Encode(), nil)
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("get %s: %v", endpoint, err)
	}
	defer resp.Body.Close()
	var m rpcMsg
	_ = json.NewDecoder(resp.Body).Decode(&m)
	return resp.StatusCode, m
}

func fsGetEntries(t *testing.T, m rpcMsg) []rpcMsg {
	t.Helper()
	raw, ok := m["entries"].([]any)
	if !ok {
		t.Fatalf("entries missing or not an array: %v", m)
	}
	out := make([]rpcMsg, 0, len(raw))
	for _, v := range raw {
		if o, ok := v.(map[string]any); ok {
			out = append(out, o)
		}
	}
	return out
}

// Dir mode lists one level — files AND dirs, size, ignored flags — and
// never descends on its own.
func TestFsListDirMode(t *testing.T) {
	repo := t.TempDir()
	gitRun(t, repo, "init")
	gitRun(t, repo, "config", "user.email", "t@t")
	gitRun(t, repo, "config", "user.name", "t")
	seed := func(rel, content string) {
		p := filepath.Join(repo, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatalf("mkdir: %v", err)
		}
		if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
			t.Fatalf("seed %s: %v", rel, err)
		}
	}
	seed("a.txt", "one\n")
	seed("sub/in.txt", "x\n")
	seed(".gitignore", "ignored.txt\nignored_dir/\n")
	seed("ignored.txt", "i\n")
	seed("ignored_dir/x.txt", "i\n")
	gitRun(t, repo, "add", "a.txt", "sub/in.txt", ".gitignore")
	gitRun(t, repo, "commit", "-m", "init")

	oldToken, oldHub := *token, bridgeHub
	defer func() { *token, bridgeHub = oldToken, oldHub }()
	*token = ""
	bridgeHub = newHub()
	plantSessionCwd(t, bridgeHub, "SID", repo)

	srv := httptest.NewServer(http.HandlerFunc(fsListHandler))
	defer srv.Close()

	// Root level: a.txt + sub/ + .gitignore + ignored* — but NOT
	// sub/in.txt (one level only) and never .git itself.
	st, m := fsGet(t, srv, "/fs/list", "",
		url.Values{"sessionId": {"SID"}, "path": {""}})
	if st != http.StatusOK {
		t.Fatalf("status = %d, body %v", st, m)
	}
	entries := fsGetEntries(t, m)
	byName := map[string]rpcMsg{}
	for _, e := range entries {
		byName[e["name"].(string)] = e
	}
	if byName["a.txt"] == nil || byName["a.txt"]["kind"] != "file" {
		t.Fatalf("a.txt entry wrong: %v", byName["a.txt"])
	}
	if sz, _ := byName["a.txt"]["size"].(float64); sz != 4 {
		t.Fatalf("a.txt size = %v, want 4", byName["a.txt"]["size"])
	}
	sub := byName["sub"]
	if sub == nil || sub["kind"] != "directory" || sub["relPath"] != "sub" {
		t.Fatalf("sub entry wrong: %v", sub)
	}
	if _, hasGit := byName[".git"]; hasGit {
		t.Fatalf(".git listed: %v", entries)
	}
	for name, e := range byName {
		if name == "sub/in.txt" || strings.Contains(e["relPath"].(string), "/") {
			t.Fatalf("descended below one level: %v", e)
		}
	}
	if byName["ignored.txt"]["ignored"] != true {
		t.Fatalf("ignored.txt not flagged: %v", byName["ignored.txt"])
	}
	if byName["ignored_dir"]["ignored"] != true {
		t.Fatalf("ignored_dir not flagged: %v", byName["ignored_dir"])
	}
	if byName["a.txt"]["ignored"] == true {
		t.Fatalf("tracked file flagged ignored: %v", byName["a.txt"])
	}

	// A subdirectory listing carries parent-relative relPaths.
	st, m = fsGet(t, srv, "/fs/list", "",
		url.Values{"sessionId": {"SID"}, "path": {"sub"}})
	if st != http.StatusOK {
		t.Fatalf("sub status = %d, body %v", st, m)
	}
	entries = fsGetEntries(t, m)
	if len(entries) != 1 || entries[0]["relPath"] != "sub/in.txt" {
		t.Fatalf("sub entries = %v, want [sub/in.txt]", entries)
	}

	// Traversal and absolute paths → 403; a file as dir → 400; a missing
	// dir → 404.
	for _, p := range []string{"..", "../x", "sub/../../x", ".", "./.."} {
		st, _ = fsGet(t, srv, "/fs/list", "",
			url.Values{"sessionId": {"SID"}, "path": {p}})
		if p == "." {
			// "." is the root itself — allowed.
			if st != http.StatusOK {
				t.Fatalf("path %q status = %d", p, st)
			}
			continue
		}
		if st != http.StatusForbidden {
			t.Fatalf("path %q status = %d, want 403", p, st)
		}
	}
	if abs, err := filepath.Abs(repo); err == nil {
		st, _ = fsGet(t, srv, "/fs/list", "",
			url.Values{"sessionId": {"SID"}, "path": {abs}})
		if st != http.StatusForbidden {
			t.Fatalf("abs path status = %d, want 403", st)
		}
	}
	st, _ = fsGet(t, srv, "/fs/list", "",
		url.Values{"sessionId": {"SID"}, "path": {"a.txt"}})
	if st != http.StatusBadRequest {
		t.Fatalf("file-as-dir status = %d, want 400", st)
	}
	st, _ = fsGet(t, srv, "/fs/list", "",
		url.Values{"sessionId": {"SID"}, "path": {"nope"}})
	if st != http.StatusNotFound {
		t.Fatalf("missing dir status = %d, want 404", st)
	}
	st, _ = fsGet(t, srv, "/fs/list", "",
		url.Values{"sessionId": {"NOPE"}, "path": {""}})
	if st != http.StatusNotFound {
		t.Fatalf("unknown session status = %d, want 404", st)
	}
}

// /fs/read returns text content, marks binaries by mime, enforces
// containment, and flags >1 MiB reads as truncated.
func TestFsReadEndpoint(t *testing.T) {
	repo := t.TempDir()
	if err := os.WriteFile(filepath.Join(repo, "a.txt"),
		[]byte("hello\nworld\n"), 0o644); err != nil {
		t.Fatalf("seed: %v", err)
	}
	if err := os.WriteFile(filepath.Join(repo, "bin.dat"),
		[]byte{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00},
		0o644); err != nil {
		t.Fatalf("seed binary: %v", err)
	}
	outside := filepath.Join(t.TempDir(), "out.txt")
	if err := os.WriteFile(outside, []byte("secret\n"), 0o644); err != nil {
		t.Fatalf("seed outside: %v", err)
	}
	if err := os.Symlink(outside, filepath.Join(repo, "escape.txt")); err != nil {
		t.Logf("symlink failed (%v) — escape case skipped", err)
	}

	oldToken, oldHub := *token, bridgeHub
	defer func() { *token, bridgeHub = oldToken, oldHub }()
	*token = ""
	bridgeHub = newHub()
	plantSessionCwd(t, bridgeHub, "SID", repo)

	srv := httptest.NewServer(http.HandlerFunc(fsReadHandler))
	defer srv.Close()

	st, m := fsGet(t, srv, "/fs/read", "",
		url.Values{"sessionId": {"SID"}, "path": {"a.txt"}})
	if st != http.StatusOK {
		t.Fatalf("status = %d, body %v", st, m)
	}
	if m["content"] != "hello\nworld\n" {
		t.Fatalf("content = %v", m["content"])
	}
	if m["truncated"] != false {
		t.Fatalf("truncated = %v", m["truncated"])
	}

	st, m = fsGet(t, srv, "/fs/read", "",
		url.Values{"sessionId": {"SID"}, "path": {"bin.dat"}})
	if st != http.StatusOK {
		t.Fatalf("binary status = %d, body %v", st, m)
	}
	if m["binary"] != true || m["mime"] == nil {
		t.Fatalf("binary marker = %v", m)
	}
	if _, hasContent := m["content"]; hasContent {
		t.Fatalf("binary payload carried content: %v", m)
	}

	// Traversal/abs → 403; a symlink escaping the workspace → 403; a dir
	// → 400; missing → 404.
	for _, p := range []string{"..", "sub/../..", outside} {
		st, _ = fsGet(t, srv, "/fs/read", "",
			url.Values{"sessionId": {"SID"}, "path": {p}})
		if st != http.StatusForbidden {
			t.Fatalf("path %q status = %d, want 403", p, st)
		}
	}
	st, _ = fsGet(t, srv, "/fs/read", "",
		url.Values{"sessionId": {"SID"}, "path": {"escape.txt"}})
	if st != http.StatusForbidden && st != http.StatusNotFound {
		t.Fatalf("escape.txt status = %d, want 403", st)
	}
	st, _ = fsGet(t, srv, "/fs/read", "",
		url.Values{"sessionId": {"SID"}, "path": {""}})
	if st != http.StatusBadRequest {
		t.Fatalf("empty path status = %d, want 400", st)
	}
	st, _ = fsGet(t, srv, "/fs/read", "",
		url.Values{"sessionId": {"SID"}, "path": {"nope.txt"}})
	if st != http.StatusNotFound {
		t.Fatalf("missing file status = %d, want 404", st)
	}
}

// The ?cwd= fallback: inside a hosted workspace → listed; outside every
// hosted workspace → 403.
func TestFsCwdParam(t *testing.T) {
	repo := t.TempDir()
	gitRun(t, repo, "init")
	gitRun(t, repo, "config", "user.email", "t@t")
	gitRun(t, repo, "config", "user.name", "t")
	if err := os.WriteFile(filepath.Join(repo, "a.txt"),
		[]byte("one\n"), 0o644); err != nil {
		t.Fatalf("seed: %v", err)
	}
	other := t.TempDir()

	oldToken, oldHub := *token, bridgeHub
	defer func() { *token, bridgeHub = oldToken, oldHub }()
	*token = ""
	bridgeHub = newHub()
	plantSessionCwd(t, bridgeHub, "SID", repo)

	srv := httptest.NewServer(http.HandlerFunc(fsListHandler))
	defer srv.Close()

	st, m := fsGet(t, srv, "/fs/list", "", url.Values{
		"cwd": {repo}, "path": {""},
	})
	if st != http.StatusOK {
		t.Fatalf("cwd status = %d, body %v", st, m)
	}
	if len(fsGetEntries(t, m)) != 1 {
		t.Fatalf("cwd entries = %v", m["entries"])
	}

	st, _ = fsGet(t, srv, "/fs/list", "", url.Values{
		"cwd": {other}, "path": {""},
	})
	if st != http.StatusForbidden {
		t.Fatalf("outside cwd status = %d, want 403", st)
	}

	// GET flat mode (no path key) over cwd → the git ls-files shape.
	st, m = fsGet(t, srv, "/fs/list", "", url.Values{"cwd": {repo}})
	if st != http.StatusOK {
		t.Fatalf("flat cwd status = %d, body %v", st, m)
	}
	if _, ok := m["files"]; !ok {
		t.Fatalf("flat mode missing files: %v", m)
	}
}
