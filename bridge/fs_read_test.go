// /fs/read tests: the endpoint serves the source viewer — text content up
// to fsReadMaxBytes with a truncated flag, {binary,mime} for binaries,
// workspace-confined, token-gated (W8.1).
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

// fsReadGet issues GET /fs/read with the query params and returns
// (status, decoded body).
func fsReadGet(t *testing.T, srv *httptest.Server, token string,
	q url.Values) (int, rpcMsg) {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet,
		srv.URL+"/fs/read?"+q.Encode(), nil)
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("get /fs/read: %v", err)
	}
	defer resp.Body.Close()
	var m rpcMsg
	_ = json.NewDecoder(resp.Body).Decode(&m)
	return resp.StatusCode, m
}

// Text within the cap round-trips verbatim; text past it is cut at
// exactly fsReadMaxBytes with truncated=true.
func TestFsReadTextAndTruncation(t *testing.T) {
	workspace := t.TempDir()
	oldToken, oldHub := *token, bridgeHub
	defer func() { *token, bridgeHub = oldToken, oldHub }()
	*token = ""
	bridgeHub = newHub()
	plantSessionCwd(t, bridgeHub, "SID", workspace)
	srv := httptest.NewServer(http.HandlerFunc(fsReadHandler))
	defer srv.Close()

	small := "package main\n\nfunc main() {}\n"
	if err := os.WriteFile(filepath.Join(workspace, "small.go"),
		[]byte(small), 0o644); err != nil {
		t.Fatalf("seed small: %v", err)
	}
	big := strings.Repeat("a", fsReadMaxBytes+64)
	if err := os.WriteFile(filepath.Join(workspace, "big.txt"),
		[]byte(big), 0o644); err != nil {
		t.Fatalf("seed big: %v", err)
	}

	q := url.Values{"sessionId": {"SID"}, "path": {"small.go"}}
	st, m := fsReadGet(t, srv, "", q)
	if st != http.StatusOK {
		t.Fatalf("small: status %d, body %v", st, m)
	}
	if m["content"] != small {
		t.Fatalf("small content = %v", m["content"])
	}
	if m["truncated"] != false {
		t.Fatalf("small truncated = %v", m["truncated"])
	}

	q.Set("path", "big.txt")
	st, m = fsReadGet(t, srv, "", q)
	if st != http.StatusOK {
		t.Fatalf("big: status %d, body %v", st, m)
	}
	if m["truncated"] != true {
		t.Fatalf("big truncated = %v", m["truncated"])
	}
	content, _ := m["content"].(string)
	if len(content) != fsReadMaxBytes {
		t.Fatalf("big content len = %d, want %d", len(content), fsReadMaxBytes)
	}
	if !strings.HasPrefix(big, content) {
		t.Fatalf("big content is not a prefix of the file")
	}
}

// NUL-containing bytes must never reach the content field — the viewer
// gets {binary:true, mime} instead.
func TestFsReadBinary(t *testing.T) {
	workspace := t.TempDir()
	oldToken, oldHub := *token, bridgeHub
	defer func() { *token, bridgeHub = oldToken, oldHub }()
	*token = ""
	bridgeHub = newHub()
	plantSessionCwd(t, bridgeHub, "SID", workspace)
	srv := httptest.NewServer(http.HandlerFunc(fsReadHandler))
	defer srv.Close()

	bin := append([]byte{0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a},
		make([]byte, 64)...)
	if err := os.WriteFile(filepath.Join(workspace, "img.png"),
		bin, 0o644); err != nil {
		t.Fatalf("seed bin: %v", err)
	}
	st, m := fsReadGet(t, srv, "", url.Values{
		"sessionId": {"SID"}, "path": {"img.png"}})
	if st != http.StatusOK {
		t.Fatalf("binary: status %d, body %v", st, m)
	}
	if m["binary"] != true {
		t.Fatalf("binary flag = %v (body %v)", m["binary"], m)
	}
	if m["content"] != nil {
		t.Fatalf("binary response carried content")
	}
	mime, _ := m["mime"].(string)
	if mime == "" {
		t.Fatalf("binary mime missing: %v", m)
	}
}

// Directories, escapes and missing params are rejected; unknown sessions
// 404 like every other /fs endpoint.
func TestFsReadRejections(t *testing.T) {
	workspace := t.TempDir()
	oldToken, oldHub := *token, bridgeHub
	defer func() { *token, bridgeHub = oldToken, oldHub }()
	*token = ""
	bridgeHub = newHub()
	plantSessionCwd(t, bridgeHub, "SID", workspace)
	srv := httptest.NewServer(http.HandlerFunc(fsReadHandler))
	defer srv.Close()

	if err := os.MkdirAll(filepath.Join(workspace, "sub"), 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	cases := []struct {
		name string
		q    url.Values
		want int
	}{
		{"directory", url.Values{"sessionId": {"SID"}, "path": {"sub"}},
			http.StatusBadRequest},
		{"escape", url.Values{"sessionId": {"SID"}, "path": {"../x"}},
			http.StatusForbidden},
		{"unknown session", url.Values{"sessionId": {"NOPE"}, "path": {"a"}},
			http.StatusNotFound},
		{"missing path", url.Values{"sessionId": {"SID"}},
			http.StatusBadRequest},
	}
	for _, c := range cases {
		st, m := fsReadGet(t, srv, "", c.q)
		if st != c.want {
			t.Fatalf("%s: status %d (body %v), want %d",
				c.name, st, m, c.want)
		}
	}
}
