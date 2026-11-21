// Package main builds libdevingotailnet.so — a c-shared library that runs a
// tsnet node inside the app process and exposes start/status/forward/stop/
// logout directly as JNI symbols on com.devingo.app.tailnet.TailnetNative,
// so the app needs no separate Tailscale install and no C glue.
package main

/*
#cgo CFLAGS: -D_JNI_IMPLEMENTATION_
#include <jni.h>
#include <stdlib.h>

static const char* jstr_get(JNIEnv* env, jstring s) {
	return (*env)->GetStringUTFChars(env, s, NULL);
}
static void jstr_release(JNIEnv* env, jstring s, const char* p) {
	(*env)->ReleaseStringUTFChars(env, s, p);
}
static jstring jstr_new(JNIEnv* env, const char* p) {
	return (*env)->NewStringUTF(env, p);
}
*/
import "C"

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
	"unsafe"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/net/tsaddr"
	"tailscale.com/tsnet"
)

func main() {}

// ---- shared node state ---------------------------------------------------

var (
	nodeMu    sync.Mutex
	srv       *tsnet.Server
	nodeClose context.CancelFunc

	stateMu  sync.RWMutex
	curState = "NoState"
	authURL  string
	lastErr  string

	// Loopback forwarders keyed by "host:port"; they always dial through the
	// current server pointer, so they stay correct across node restarts.
	fwdMu     sync.Mutex
	listeners = map[string]net.Listener{}
)

func discardLogf(string, ...any) {}

func goString(env *C.JNIEnv, s C.jstring) string {
	p := C.jstr_get(env, s)
	if p == nil {
		return ""
	}
	defer C.jstr_release(env, s, p)
	return C.GoString(p)
}

func javaString(env *C.JNIEnv, s string) C.jstring {
	cs := C.CString(s)
	defer C.free(unsafe.Pointer(cs))
	return C.jstr_new(env, cs)
}

func setState(s string) { stateMu.Lock(); curState = s; stateMu.Unlock() }
func getState() string  { stateMu.RLock(); defer stateMu.RUnlock(); return curState }
func setAuth(u string)  { stateMu.Lock(); authURL = u; stateMu.Unlock() }
func getAuth() string   { stateMu.RLock(); defer stateMu.RUnlock(); return authURL }
func setErr(e string)   { stateMu.Lock(); lastErr = e; stateMu.Unlock() }
func getErr() string    { stateMu.RLock(); defer stateMu.RUnlock(); return lastErr }

// ---- JNI exports -----------------------------------------------------------

//export Java_com_devingo_app_tailnet_TailnetNative_start
func Java_com_devingo_app_tailnet_TailnetNative_start(env *C.JNIEnv, _ C.jclass, stateDir, cacheDir, hostname C.jstring) C.jstring {
	sd := goString(env, stateDir)
	os.Setenv("HOME", sd)
	os.Setenv("TMPDIR", goString(env, cacheDir))

	// One lock spans the whole create/assign so a second call can't leak a
	// half-started Server, and stop() always sees a fully assigned srv.
	nodeMu.Lock()
	if srv != nil {
		nodeMu.Unlock()
		return javaString(env, "")
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &tsnet.Server{
		Dir:      sd,
		Hostname: goString(env, hostname),
		Logf:     discardLogf,
		UserLogf: discardLogf,
	}
	if err := s.Start(); err != nil {
		nodeMu.Unlock()
		cancel()
		setErr(err.Error())
		return javaString(env, err.Error())
	}
	lc, err := s.LocalClient()
	if err != nil {
		nodeMu.Unlock()
		cancel()
		s.Close()
		setErr(err.Error())
		return javaString(env, err.Error())
	}
	srv, nodeClose = s, cancel
	nodeMu.Unlock()
	setErr("")
	go watchIPN(ctx, lc)
	return javaString(env, "")
}

// watchIPN records the latest backend state/auth URL, and kicks off the
// interactive login once when the backend first reports NeedsLogin.
func watchIPN(ctx context.Context, lc *local.Client) {
	w, err := lc.WatchIPNBus(ctx, ipn.NotifyInitialState|ipn.NotifyNoPrivateKeys)
	if err != nil {
		setErr(err.Error())
		return
	}
	defer w.Close()
	loginStarted := false
	for {
		n, err := w.Next()
		if err != nil {
			return // ctx cancelled or node stopped
		}
		if n.State != nil {
			setState(n.State.String())
			switch *n.State {
			case ipn.Running:
				// Login flow finished; drop the URL and re-arm so a later
				// NeedsLogin (expired key, removed device) starts fresh.
				setAuth("")
				loginStarted = false
			case ipn.NeedsLogin:
				if getAuth() == "" && !loginStarted {
					loginStarted = true
					go lc.StartLoginInteractive(ctx)
				}
			}
		}
		if n.BrowseToURL != nil && *n.BrowseToURL != "" {
			setAuth(*n.BrowseToURL)
		}
		if n.ErrMessage != nil {
			setErr(*n.ErrMessage)
		}
	}
}

type statusJSON struct {
	State   string   `json:"state"`
	AuthURL *string  `json:"authUrl"`
	IPs     []string `json:"ips"`
	DNSName string   `json:"dnsName"`
	Tailnet string   `json:"tailnet"`
	Error   *string  `json:"error"`
}

//export Java_com_devingo_app_tailnet_TailnetNative_status
func Java_com_devingo_app_tailnet_TailnetNative_status(env *C.JNIEnv, _ C.jclass) C.jstring {
	out := statusJSON{State: getState(), IPs: []string{}}
	if u := getAuth(); u != "" {
		out.AuthURL = &u
	}
	if e := getErr(); e != "" {
		out.Error = &e
	}
	nodeMu.Lock()
	s := srv
	nodeMu.Unlock()
	if s != nil && out.State == "Running" {
		if lc, err := s.LocalClient(); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			st, err := lc.Status(ctx)
			cancel()
			if err == nil {
				for _, a := range st.TailscaleIPs {
					out.IPs = append(out.IPs, a.String())
				}
				if st.Self != nil {
					out.DNSName = strings.TrimSuffix(st.Self.DNSName, ".")
				}
				if st.CurrentTailnet != nil && st.CurrentTailnet.Name != "" {
					out.Tailnet = st.CurrentTailnet.Name
				} else {
					out.Tailnet = st.MagicDNSSuffix
				}
			}
		}
	}
	b, _ := json.Marshal(out)
	return javaString(env, string(b))
}

//export Java_com_devingo_app_tailnet_TailnetNative_forward
func Java_com_devingo_app_tailnet_TailnetNative_forward(env *C.JNIEnv, _ C.jclass, jtarget C.jstring) C.jint {
	target := goString(env, jtarget)
	if !validForwardTarget(target) {
		return -1
	}
	// Lookup, listen and insert under one lock: a losing concurrent call
	// would otherwise create a listener that stop() can never reach.
	fwdMu.Lock()
	if ln, ok := listeners[target]; ok {
		port := ln.Addr().(*net.TCPAddr).Port
		fwdMu.Unlock()
		return C.jint(port)
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		fwdMu.Unlock()
		return -1
	}
	listeners[target] = ln
	fwdMu.Unlock()
	go serveForward(ln, target)
	return C.jint(ln.Addr().(*net.TCPAddr).Port)
}

// Only tailnet destinations may be forwarded: CGNAT IPv4 (100.64.0.0/10) or a
// MagicDNS *.ts.net name, with a real port.
func validForwardTarget(target string) bool {
	host, portStr, err := net.SplitHostPort(target)
	if err != nil {
		return false
	}
	if p, err := strconv.Atoi(portStr); err != nil || p < 1 || p > 65535 {
		return false
	}
	if addr, err := netip.ParseAddr(host); err == nil {
		return addr.Is4() && tsaddr.CGNATRange().Contains(addr)
	}
	return strings.HasSuffix(host, ".ts.net")
}

func serveForward(ln net.Listener, target string) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go proxyConn(c, target)
	}
}

func proxyConn(c net.Conn, target string) {
	defer c.Close()
	nodeMu.Lock()
	s := srv
	nodeMu.Unlock()
	if s == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	rc, err := s.Dial(ctx, "tcp", target)
	cancel()
	if err != nil {
		return
	}
	defer rc.Close()
	go func() {
		io.Copy(rc, c)
		rc.Close()
	}()
	io.Copy(c, rc)
}

//export Java_com_devingo_app_tailnet_TailnetNative_stop
func Java_com_devingo_app_tailnet_TailnetNative_stop(_ *C.JNIEnv, clazz C.jclass) {
	nodeMu.Lock()
	s, closeNode := srv, nodeClose
	srv, nodeClose = nil, nil
	nodeMu.Unlock()
	if closeNode != nil {
		closeNode()
	}
	fwdMu.Lock()
	for k, ln := range listeners {
		ln.Close()
		delete(listeners, k)
	}
	fwdMu.Unlock()
	if s != nil {
		s.Close()
	}
	setState("Stopped")
	setAuth("")
}

//export Java_com_devingo_app_tailnet_TailnetNative_logout
func Java_com_devingo_app_tailnet_TailnetNative_logout(env *C.JNIEnv, clazz C.jclass) {
	nodeMu.Lock()
	s := srv
	nodeMu.Unlock()
	if s != nil {
		if lc, err := s.LocalClient(); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			lc.Logout(ctx)
			cancel()
		}
	}
	Java_com_devingo_app_tailnet_TailnetNative_stop(env, clazz)
}
