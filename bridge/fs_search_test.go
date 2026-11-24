// /fs/search tests: the endpoint fuzzy-matches files AND directories
// under a session workspace for the composer's @-picker — cwd-relative,
// subtree-confined, token-gated, with dependency/VCS dirs pruned (A7.3).
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

// fsSearchGet issues GET /fs/search and returns (status, body).
func fsSearchGet(t *testing.T, srv *httptest.Server, token, query string) (int, rpcMsg) {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, srv.URL+"/fs/search?"+query, nil)
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("get /fs/search: %v", err)
	}
	defer resp.Body.Close()
	var m rpcMsg
	_ = json.NewDecoder(resp.Body).Decode(&m)
	return resp.StatusCode, m
}

func fsSearchPaths(t *testing.T, m rpcMsg) map[string]string {
	t.Helper()
	raw, ok := m["items"].([]any)
	if !ok {
		t.Fatalf("items missing or not an array: %v", m)
	}
	out := make(map[string]string, len(raw))
	for _, v := range raw {
		item, _ := v.(map[string]any)
		p, _ := item["relPath"].(string)
		k, _ := item["kind"].(string)
		out[p] = k
	}
	return out
}

func TestFsSearchEndpoint(t *testing.T) {
	root := t.TempDir()
	mk := func(rel string) {
		t.Helper()
		if err := os.MkdirAll(filepath.Dir(filepath.Join(root, rel)), 0o755); err != nil {
			t.Fatalf("mkdir %s: %v", rel, err)
		}
		if err := os.WriteFile(filepath.Join(root, rel), []byte("x\n"), 0o644); err != nil {
			t.Fatalf("seed %s: %v", rel, err)
		}
	}
	mkdir := func(rel string) {
		t.Helper()
		if err := os.MkdirAll(filepath.Join(root, rel), 0o755); err != nil {
			t.Fatalf("mkdir %s: %v", rel, err)
		}
	}
	mk("src/main.go")
	mk("src/util.go")
	mk("docs/readme.md")
	mk("node_modules/dep/index.js") // pruned subtree
	mk("build/out.bin")             // pruned subtree
	mkdir("emptydir")               // a directory with no files inside

	oldToken, oldHub := *token, bridgeHub
	defer func() { *token, bridgeHub = oldToken, oldHub }()
	*token = ""
	bridgeHub = newHub()
	plantSessionCwd(t, bridgeHub, "SID", root)

	srv := httptest.NewServer(http.HandlerFunc(fsSearchHandler))
	defer srv.Close()

	// Empty query returns files AND directories; skipped trees stay out.
	st, m := fsSearchGet(t, srv, "", "sessionId=SID&limit=50")
	if st != http.StatusOK {
		t.Fatalf("status = %d, body %v", st, m)
	}
	got := fsSearchPaths(t, m)
	for want, kind := range map[string]string{
		"src":            "directory",
		"src/main.go":    "file",
		"src/util.go":    "file",
		"docs":           "directory",
		"docs/readme.md": "file",
		"emptydir":       "directory",
	} {
		if got[want] != kind {
			t.Fatalf("items[%q] = %q, want %q (all: %v)", want, got[want], kind, got)
		}
	}
	for p := range got {
		if strings.HasPrefix(p, "node_modules") || strings.HasPrefix(p, "build") {
			t.Fatalf("skipped tree leaked into results: %v", got)
		}
	}
	if m["truncated"] != false {
		t.Fatalf("truncated = %v", m["truncated"])
	}

	// Fuzzy query ranks the basename hit first and prunes non-matches.
	st, m = fsSearchGet(t, srv, "", "sessionId=SID&q=main&limit=10")
	if st != http.StatusOK {
		t.Fatalf("query status = %d, body %v", st, m)
	}
	got = fsSearchPaths(t, m)
	if len(got) == 0 {
		t.Fatalf("query returned no items")
	}
	if got["src/main.go"] != "file" {
		t.Fatalf("main.go missing from query results: %v", got)
	}
	first, _ := m["items"].([]any)
	if f, _ := first[0].(map[string]any); f["relPath"] != "src/main.go" {
		t.Fatalf("first result = %v, want src/main.go", f["relPath"])
	}

	// A session rooted in a subdirectory sees only its own subtree.
	sub := filepath.Join(root, "subws")
	mkdir("subws")
	if err := os.WriteFile(filepath.Join(sub, "in.txt"), []byte("i\n"), 0o644); err != nil {
		t.Fatalf("seed sub: %v", err)
	}
	plantSessionCwd(t, bridgeHub, "SUB", sub)
	st, m = fsSearchGet(t, srv, "", "sessionId=SUB&limit=50")
	if st != http.StatusOK {
		t.Fatalf("subdir status = %d, body %v", st, m)
	}
	got = fsSearchPaths(t, m)
	if len(got) != 1 || got["in.txt"] != "file" {
		t.Fatalf("subdir items = %v, want {in.txt: file}", got)
	}

	// Unknown session → 404; missing sessionId → 400; POST → 405.
	st, _ = fsSearchGet(t, srv, "", "sessionId=NOPE")
	if st != http.StatusNotFound {
		t.Fatalf("unknown session status = %d", st)
	}
	st, _ = fsSearchGet(t, srv, "", "q=x")
	if st != http.StatusBadRequest {
		t.Fatalf("empty sessionId status = %d", st)
	}
	req, _ := http.NewRequest(http.MethodPost, srv.URL+"/fs/search?sessionId=SID", nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("post /fs/search: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusMethodNotAllowed {
		t.Fatalf("POST status = %d", resp.StatusCode)
	}
}
