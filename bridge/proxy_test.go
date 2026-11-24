package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

// The backend records what the proxy forwarded.
type backendRec struct {
	host, path, rawQuery string
}

func startBackend(t *testing.T) (*httptest.Server, *backendRec) {
	rec := &backendRec{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rec.host, rec.path, rec.rawQuery = r.Host, r.URL.Path, r.URL.RawQuery
		w.Header().Set("Content-Type", "text/plain")
		io.WriteString(w, "hello from dev server")
	}))
	t.Cleanup(srv.Close)
	return srv, rec
}

// proxyGet issues a request against previewProxyHandler through the real mux
// shape (path under /preview/).
func proxyGet(t *testing.T, path, token, cookie string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, path, nil)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if cookie != "" {
		req.AddCookie(&http.Cookie{Name: previewCookieName, Value: cookie})
	}
	rec := httptest.NewRecorder()
	previewProxyHandler(rec, req)
	return rec
}

func TestPreviewProxyRewritesAndForwards(t *testing.T) {
	backend, rec := startBackend(t)
	port := backend.Listener.Addr().String()[len("127.0.0.1:"):]

	oldToken := *token
	defer func() { *token = oldToken }()
	*token = "sekrit"

	res := proxyGet(t, "/preview/"+port+"/foo/bar?x=1&y=2", "sekrit", "")
	if res.Code != http.StatusOK {
		t.Fatalf("status %d body %q", res.Code, res.Body.String())
	}
	if res.Body.String() != "hello from dev server" {
		t.Fatalf("body %q", res.Body.String())
	}
	// Path prefix stripped, query preserved, Host rewritten to the target.
	if rec.path != "/foo/bar" || rec.rawQuery != "x=1&y=2" {
		t.Fatalf("backend saw %q?%q", rec.path, rec.rawQuery)
	}
	if rec.host != "localhost:"+port {
		t.Fatalf("backend host %q", rec.host)
	}
}

func TestPreviewProxyAuth(t *testing.T) {
	oldToken := *token
	defer func() { *token = oldToken }()
	*token = "sekrit"

	// No credentials → 401.
	if res := proxyGet(t, "/preview/9/", "", ""); res.Code != http.StatusUnauthorized {
		t.Fatalf("no-creds status %d", res.Code)
	}
	// Wrong cookie → 401.
	if res := proxyGet(t, "/preview/9/", "", "wrong"); res.Code != http.StatusUnauthorized {
		t.Fatalf("bad-cookie status %d", res.Code)
	}
	// Right cookie passes auth; the dead port then 502s — proof the auth
	// gate, not the dial, is what rejected the earlier requests.
	res := proxyGet(t, "/preview/9/", "", "sekrit")
	if res.Code == http.StatusUnauthorized {
		t.Fatalf("cookie auth rejected")
	}
}

func TestPreviewProxyPortValidation(t *testing.T) {
	oldToken := *token
	defer func() { *token = oldToken }()
	*token = ""

	for _, p := range []string{
		"/preview/abc/", "/preview/0/", "/preview/-1/", "/preview/65536/",
		"/preview//", "/preview/12x34/",
	} {
		if res := proxyGet(t, p, "", ""); res.Code != http.StatusBadRequest {
			t.Fatalf("%s -> %d, want 400", p, res.Code)
		}
	}
}

func TestPreviewAuthSetsCookieAndRedirects(t *testing.T) {
	oldToken := *token
	defer func() { *token = oldToken }()
	*token = "sekrit"

	req := httptest.NewRequest(http.MethodGet, "/preview-auth?port=8080", nil)
	req.Header.Set("Authorization", "Bearer sekrit")
	rec := httptest.NewRecorder()
	previewAuthHandler(rec, req)
	res := rec.Result()
	if res.StatusCode != http.StatusFound {
		t.Fatalf("status %d", res.StatusCode)
	}
	if loc := res.Header.Get("Location"); loc != "/preview/8080/" {
		t.Fatalf("Location %q", loc)
	}
	var cookie *http.Cookie
	for _, c := range res.Cookies() {
		if c.Name == previewCookieName {
			cookie = c
		}
	}
	if cookie == nil || cookie.Value != "sekrit" || cookie.Path != "/preview" ||
		cookie.MaxAge != 86400 || cookie.SameSite != http.SameSiteStrictMode {
		t.Fatalf("cookie %+v", cookie)
	}

	// Unauthorized → 401, and a bad port → 400.
	req = httptest.NewRequest(http.MethodGet, "/preview-auth?port=8080", nil)
	rec = httptest.NewRecorder()
	previewAuthHandler(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("unauth status %d", rec.Code)
	}
	req = httptest.NewRequest(http.MethodGet, "/preview-auth?port=nope", nil)
	req.Header.Set("Authorization", "Bearer sekrit")
	rec = httptest.NewRecorder()
	previewAuthHandler(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("bad port status %d", rec.Code)
	}
}
