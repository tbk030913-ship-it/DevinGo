// Phone pairing: serves a QR page the operator opens on the PC, carrying
// everything DevinGo needs to point at this bridge without typing.
//
// The JSON payload is {v, hosts, tail, token}: `hosts` lists the bridge's
// LAN addresses best-first, `tail` its Tailscale address (for phones that
// go through the embedded tailnet), `token` the bearer token. The token is only handed to
// loopback viewers — the QR page opened from another LAN device renders a
// tokenless payload so the secret never leaves the operator's own screen.
package main

import (
	"encoding/json"
	"fmt"
	"html"
	"log"
	"net"
	"net/http"
	"strings"

	qrcode "github.com/skip2/go-qrcode"
)

type pairPayload struct {
	V     int      `json:"v"`
	Hosts []string `json:"hosts"`
	Tail  string   `json:"tail,omitempty"`
	Token string   `json:"token,omitempty"`
}

func listenPort() string {
	_, port, err := net.SplitHostPort(*addr)
	if err == nil && port != "" {
		return port
	}
	return strings.TrimPrefix(*addr, ":")
}

func isCGNAT(ip net.IP) bool {
	v4 := ip.To4()
	return v4 != nil && v4[0] == 100 && v4[1] >= 64 && v4[1] <= 127
}

// hostRank orders LAN candidates for phones: a real home subnet first, then
// other private ranges, then anything else. 198.18.0.0/15 (IANA benchmark,
// hijacked by Clash-style TUN adapters) and link-local are dropped entirely.
func hostRank(ip net.IP) int {
	v4 := ip.To4()
	if v4 == nil {
		return -1
	}
	switch {
	case v4[0] == 198 && v4[1]&0xFE == 18:
		return -1
	case v4[0] == 169 && v4[1] == 254:
		return -1
	case v4[0] == 192 && v4[1] == 168:
		return 0
	case v4[0] == 10:
		return 1
	case v4[0] == 172 && v4[1] >= 16 && v4[1] <= 31:
		return 2
	default:
		return 3
	}
}

// pairJSON lists LAN candidates best-first plus the tailnet address, so the
// phone can probe candidates in order and pick the first reachable one.
func pairJSON(withToken bool) []byte {
	p := pairPayload{V: 1, Hosts: []string{}}
	port := listenPort()
	ranked := map[int][]string{}
	for _, ipStr := range lanIPs() {
		ip := net.ParseIP(ipStr)
		if ip == nil {
			continue
		}
		if isCGNAT(ip) {
			if p.Tail == "" {
				p.Tail = ipStr + ":" + port
			}
			continue
		}
		if r := hostRank(ip); r >= 0 {
			ranked[r] = append(ranked[r], ipStr+":"+port)
		}
	}
	for r := 0; r <= 3; r++ {
		p.Hosts = append(p.Hosts, ranked[r]...)
	}
	if withToken {
		p.Token = *token
	}
	b, _ := json.Marshal(p)
	return b
}

// pairQR renders the payload as an ASCII QR on the operator's console —
// token included, same trust level as the terminal itself.
func printPairQR() {
	payload := pairJSON(true)
	if len(payload) <= len(`{"v":1,"hosts":[]}`) {
		return // no usable address
	}
	fmt.Println()
	if q, err := qrcode.New(string(payload), qrcode.Medium); err == nil {
		fmt.Println(q.ToSmallString(false))
	}
	fmt.Println("  配对二维码（DevinGo → 扫码连接）；网页版: http://localhost:" + listenPort() + "/pair")
	fmt.Println("  payload: " + string(payload))
}

func isLoopbackRemote(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func pairHandler(w http.ResponseWriter, r *http.Request) {
	local := isLoopbackRemote(r)
	payload := html.EscapeString(string(pairJSON(local)))
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, `<!doctype html><meta name=viewport content="width=device-width">
<title>DevinGo 配对</title>
<style>
body{font-family:system-ui,sans-serif;max-width:520px;margin:40px auto;padding:0 16px;color:#1a1a1a}
img{width:280px;height:280px;border:1px solid #ddd;border-radius:12px}
code{display:block;word-break:break-all;background:#f5f5f5;padding:10px;border-radius:8px;font-size:12px}
p{color:#555}
</style>
<h3>DevinGo 扫码连接</h3>
<img src="/pair/qr.png" alt="pairing qr">
<p>打开手机 DevinGo，点左侧二维码图标扫描；没有相机时把下面的内容粘进“粘贴配对码”。</p>
<code>%s</code>`, payload)
	if !local && *token != "" {
		fmt.Fprint(w, `<p>注意：从非本机打开此页，二维码不含 Token，请回电脑本机打开。</p>`)
	}
}

func pairQRHandler(w http.ResponseWriter, r *http.Request) {
	png, err := qrcode.Encode(string(pairJSON(isLoopbackRemote(r))), qrcode.Medium, 512)
	if err != nil {
		log.Printf("pair qr: %v", err)
		http.Error(w, "qr failed", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(png)
}
