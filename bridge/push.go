// Offline push (opt-in, default OFF): when the phone's process is dead and
// its foreground service is gone, nothing watches the WebSocket — so the
// bridge POSTs a bare "有新审批"/"任务完成" ping to one configured HTTP
// endpoint. Message bodies carry no session content by design (spec: 不带
// 具体内容). An ntfy topic URL (https://ntfy.sh/<topic> or self-hosted) is
// the tested path; any webhook that treats the request body as the message
// works the same. Delivery is fire-and-forget: failures log only, and no
// hub path ever blocks on push.
package main

import (
	"log"
	"net/http"
	"strings"
	"time"
)

// pushTimeout bounds one push POST end to end.
const pushTimeout = 5 * time.Second

// bridgePush is the process-wide notifier; nil until main() configures one
// from -push / DEVINGO_PUSH_URL / bridge.json push_url. Call sites invoke
// notify unconditionally — a nil receiver is a no-op.
var bridgePush *pushNotifier

type pushNotifier struct {
	url    string
	client *http.Client
}

// newPushNotifier returns nil for an empty endpoint: push stays disabled
// and every notify call cheaply no-ops.
func newPushNotifier(url string) *pushNotifier {
	if url == "" {
		return nil
	}
	return &pushNotifier{url: url, client: &http.Client{Timeout: pushTimeout}}
}

// notify POSTs msg as the request body — ntfy shows it as the notification
// text, with Title pinning the app name. urgent lifts the ntfy Priority
// header so a pending approval surfaces above a routine completion ping.
// The POST runs on its own goroutine: hub call sites never wait on it.
func (n *pushNotifier) notify(msg string, urgent bool) {
	if n == nil {
		return
	}
	go func() {
		req, err := http.NewRequest(http.MethodPost, n.url, strings.NewReader(msg))
		if err != nil {
			log.Printf("push: bad endpoint %q: %v", n.url, err)
			return
		}
		req.Header.Set("Content-Type", "text/plain; charset=utf-8")
		req.Header.Set("Title", "DevinGo")
		if urgent {
			req.Header.Set("Priority", "4") // ntfy high priority
		}
		resp, err := n.client.Do(req)
		if err != nil {
			log.Printf("push %q -> %s failed: %v", msg, n.url, err)
			return
		}
		resp.Body.Close()
		if resp.StatusCode >= 300 {
			log.Printf("push %q -> %s: HTTP %d", msg, n.url, resp.StatusCode)
		}
	}()
}
