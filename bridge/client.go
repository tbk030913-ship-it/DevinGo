// One WebSocket connection = one Client: a set of subscribed sessions, an
// ordered outbound queue, and the read loop feeding the hub. WS disconnects
// no longer kill anything — subscriptions detach, sessions keep running for
// the idle grace window, and a re-attach replays what was missed.
package main

import (
	"context"
	"encoding/json"
	"log"
	"sync"
	"time"

	"github.com/coder/websocket"
)

type Client struct {
	hub      *Hub
	conn     *websocket.Conn
	ctx      context.Context // request-scoped ctx for Write/Ping
	remote   string
	id       string // _devingo/hello may replace the generated one
	send     chan []byte
	dead     bool               // hub.mu: no sends after true/chan close
	sessions map[*Session]bool  // hub.mu: subscribed set
	listBusy bool               // hub.mu: a _devingo/sessions call is in flight
	listNext *sessionListQuery  // hub.mu: newest queued call (supersedes earlier ones)
	kickOnce sync.Once
}

// sessionListQuery is a _devingo/sessions request waiting for the client's
// in-flight call to finish.
type sessionListQuery struct {
	id     json.RawMessage
	cwd    string
	search string
}

func newClient(h *Hub, conn *websocket.Conn, ctx context.Context, remote string) *Client {
	return &Client{
		hub:      h,
		conn:     conn,
		ctx:      ctx,
		remote:   remote,
		send:     make(chan []byte, clientSendBuffer),
		sessions: map[*Session]bool{},
	}
}

// kick closes the connection exactly once — races between the read loop,
// ping watchdog and hub-side kicks all land here.
func (c *Client) kick(code websocket.StatusCode, reason string) {
	c.kickOnce.Do(func() { c.conn.Close(code, reason) })
}

// writeLoop serializes conn writes. Ordering is what makes attach replay +
// live tail safe: everything for this client flows through c.send.
func (c *Client) writeLoop() {
	for wire := range c.send {
		if err := c.conn.Write(c.ctx, websocket.MessageText, wire); err != nil {
			log.Printf("client %s ws write failed, kicking: %v", c.id, err)
			c.kick(websocket.StatusInternalError, "write failed")
			return
		}
	}
}

// readLoop feeds client frames to the hub until the socket dies.
func (c *Client) readLoop() error {
	for {
		typ, data, err := c.conn.Read(c.ctx)
		if err != nil {
			return err
		}
		if typ != websocket.MessageText {
			continue // ACP is NDJSON text; ignore binary frames
		}
		c.hub.handleFrame(c, data)
	}
}

// pingLoop detects half-open TCP connections: a sleeping phone leaves the
// socket dead but unclosed. The pong is consumed by the read loop.
func (c *Client) pingLoop(done <-chan struct{}) {
	ticker := time.NewTicker(wsPingInterval)
	defer ticker.Stop()
	for {
		select {
		case <-done:
			return
		case <-ticker.C:
		}
		pctx, cancel := context.WithTimeout(c.ctx, wsPingTimeout)
		err := c.conn.Ping(pctx)
		cancel()
		if err != nil {
			if c.ctx.Err() != nil {
				return // handler tearing down; not a dead peer
			}
			c.hub.mu.Lock()
			id := c.id
			c.hub.mu.Unlock()
			log.Printf("client %s ws ping failed, dropping half-open connection: %v", id, err)
			c.kick(websocket.StatusInternalError, "ping timeout")
			return
		}
	}
}
