// Local web preview (N6.4): /preview/{port}/... reverse-proxies the dev
// server on localhost:{port} so the app's WebView reaches it through the
// bridge. Upgrade requests pass through untouched (HMR websockets work when
// the page uses relative ws paths).
//
// Auth: same token as the rest of the bridge, carried either as the usual
// Authorization header or as the devingo_prev cookie — a WebView can't put
// Authorization on subresource/socket loads, so /preview-auth?port=N mints
// the cookie (Path=/preview) and 302s into the tunnel.
//
// No validOrigin gate here: the proxied page's own subresources legitimately
// carry Origin: http://<bridge-host>, which is not a loopback hostname.
// SameSite=Strict on the cookie keeps cross-site pages from riding it.
package main

import (
	"net/http"
	"net/http/httputil"
	"strconv"
	"strings"
)

// previewCookieName carries the bridge token for WebView loads under
// /preview (set by /preview-auth, read by previewAuthorized).
const previewCookieName = "devingo_prev"

// previewPort validates the path segment/query as a TCP port: decimal,
// 1..65535. Everything else is a 400 — the proxy must never dial an
// attacker-chosen port... of which there are many valid ones, but only
// numeric ones in range.
func previewPort(s string) (int, bool) {
	p, err := strconv.Atoi(s)
	if err != nil || p < 1 || p > 65535 {
		return 0, false
	}
	return p, true
}

// previewAuthorized accepts the bridge bearer credentials or the
// WebView-friendly devingo_prev cookie.
func previewAuthorized(r *http.Request) bool {
	if authorized(r) {
		return true
	}
	c, err := r.Cookie(previewCookieName)
	return err == nil && c.Value == *token
}

// previewAuthHandler mints the preview cookie then 302s into the tunnel.
// Requires real authorization (bearer/query/loopback-exempt — same as /acp):
// the cookie is just the token restated, so it must not be mintable by
// someone who doesn't already hold it.
func previewAuthHandler(w http.ResponseWriter, r *http.Request) {
	if !authorized(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	port, ok := previewPort(r.URL.Query().Get("port"))
	if !ok {
		http.Error(w, "bad port", http.StatusBadRequest)
		return
	}
	http.SetCookie(w, &http.Cookie{
		Name:     previewCookieName,
		Value:    *token,
		Path:     "/preview",
		MaxAge:   86400,
		HttpOnly: true,
		SameSite: http.SameSiteStrictMode,
	})
	http.Redirect(w, r, "/preview/"+strconv.Itoa(port)+"/", http.StatusFound)
}

// stripPreviewCookie drops the bridge's preview cookie from an outbound
// request while keeping the target app's own cookies — forwarding
// devingo_prev would hand the raw bridge token to whatever happens to
// listen on the port.
func stripPreviewCookie(req *http.Request) {
	raw := req.Header.Get("Cookie")
	if raw == "" {
		return
	}
	var kept []string
	for _, part := range strings.Split(raw, ";") {
		part = strings.TrimSpace(part)
		name, _, _ := strings.Cut(part, "=")
		if name != "" && name != previewCookieName {
			kept = append(kept, part)
		}
	}
	if len(kept) == 0 {
		req.Header.Del("Cookie")
	} else {
		req.Header.Set("Cookie", strings.Join(kept, "; "))
	}
}

// previewProxyHandler forwards /preview/{port}/rest to
// http://localhost:{port}/rest, preserving the query string.
func previewProxyHandler(w http.ResponseWriter, r *http.Request) {
	if !previewAuthorized(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	seg, rest, _ := strings.Cut(strings.TrimPrefix(r.URL.Path, "/preview/"), "/")
	port, ok := previewPort(seg)
	if !ok {
		http.Error(w, "bad port", http.StatusBadRequest)
		return
	}
	target := "localhost:" + strconv.Itoa(port)
	proxy := &httputil.ReverseProxy{
		Director: func(req *http.Request) {
			req.URL.Scheme = "http"
			req.URL.Host = target
			req.URL.Path = "/" + rest
			req.URL.RawPath = ""
			req.Host = target
			// The bridge's credentials are for the bridge only — the
			// dev server on the port must never see the bearer token,
			// the devingo_prev cookie (it IS the token), or the
			// ?token= query credential (it would land in the dev
			// server's access log and window.location.search).
			q := req.URL.Query()
			q.Del("token")
			req.URL.RawQuery = q.Encode()
			req.Header.Del("Authorization")
			stripPreviewCookie(req)
		},
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			http.Error(w, "preview target unreachable", http.StatusBadGateway)
		},
	}
	proxy.ServeHTTP(w, r)
}
