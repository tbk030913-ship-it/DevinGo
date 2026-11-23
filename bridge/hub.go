// Session-hosting core (optimization task C1, plan A): ONE resident
// `devin acp` process hosts every session. The Hub owns the process
// lifecycle, proxies `initialize`/`authenticate` itself, demuxes the
// process's NDJSON stream by sessionId, journals per-session traffic into a
// sequence-numbered ring buffer, and fans events out to subscribed clients.
//
// Wire compat with plain-ACP clients: `initialize` is answered from the
// gateway's cached result, `authenticate` is acked when the resident process
// is already authenticated, every other request is forwarded with its id
// rewritten onto a gateway-internal sequence (mapped back on the response).
// Journaled frames reach clients with a top-level "_seq" field that plain
// ACP clients ignore. `_devingo/*` requests are gateway-private and never
// reach devin.
package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	// journalMaxEntries/journalMaxBytes bound one session's event log. A
	// re-attaching client replaying past either bound is told needLoad and
	// falls back to `session/load` (devin's own replay).
	journalMaxEntries = 5000
	journalMaxBytes   = 8 << 20
	// maxHostedSessions caps sessions kept in the hub; past it the oldest
	// fully-idle session is evicted (released devin-side via session/close).
	maxHostedSessions = 32
	// initReadyTimeout bounds the resident process's gateway-side
	// initialize + authenticate handshake.
	initReadyTimeout = 2 * time.Minute
	// devinCallTimeout bounds the gateway's own calls into devin
	// (session/list for _devingo/sessions, session/close on eviction).
	devinCallTimeout = 45 * time.Second
	// clientSendBuffer must exceed the journal cap so a full attach replay
	// fits the queue; a consumer that still overflows gets kicked and must
	// re-attach (journal keeps the missed events).
	clientSendBuffer = journalMaxEntries + 512
)

// journal kinds.
const (
	jNotif = 'n' // agent notification (session/update, _devingo/* markers)
	jReq   = 'q' // pendingable agent->client request (permissions etc.)
	jResp  = 'r' // result of a session-scoped client->agent request
)

const gatewayVersion = "1.0.0-devingo"

type journalEntry struct {
	seq   uint64
	kind  byte
	frame json.RawMessage // RPC frame as clients see it (internal ids, NO _seq)
}

// agentReq is an unanswered agent->client request (a permission prompt or a
// fs/terminal callback). It outlives individual client connections: the
// first client to reply wins, everyone else gets permission_resolved.
// session is nil for requests carrying no sessionId (unscoped).
type agentReq struct {
	id      int64           // internal id the clients see
	devinID json.RawMessage // id devin assigned
	session *Session
	target  *Client         // unscoped requests: the one routed client, the only answerer
	seq     uint64          // journal seq of the request frame (0 when unscoped)
	frame   json.RawMessage // the request frame as forwarded (internal id)
	timer   *time.Timer
}

// clientReq tracks one in-flight client->agent (or gateway-internal)
// request so the response can be routed and journaled.
type clientReq struct {
	proc    *devinProc
	client  *Client // nil once the client is gone; kept for journal state
	origID  json.RawMessage
	method  string
	sid     string          // params.sessionId when session-scoped
	cwd     string          // params.cwd on session/new|load — the workspace root
	session *Session        // session marked in-flight at forward time (nil when unscoped)
	waiter  chan callResult // non-nil for the gateway's own calls
}

type callResult struct {
	frame json.RawMessage // full response frame (internal id)
	err   error
}

// Session is one hosted ACP session: its journal, subscribers, pending
// approvals and turn state. Created by session/new|load, lazily on any
// sessionId'd traffic, and survives process death (devin persists sessions
// agent-side — session/load re-hosts them).
type Session struct {
	id           string
	running      int // in-flight session/prompt count (a turn per request)
	inflight     int // in-flight session-scoped requests of any method
	subs         map[*Client]bool
	loaders      map[*Client]int // in-flight session/load refcount per client; replay routes only to them
	cwd          string          // workspace root from session/new|load params — /fs/write resolves under it
	journal      []journalEntry  // ring: oldest at index 0
	jBytes       int
	nextSeq      uint64 // 1-based; entry seq == nextSeq-1 after append
	pending      map[int64]*agentReq
	lastInfo     json.RawMessage // last session/new|load result (configOptions etc.)
	lastPrompter *Client         // preferred target for non-permission agent requests
	idleSince    time.Time       // when it last became idle (no subs/in-flight/pending)
	idleTimer    *time.Timer
}

// devinProc is the resident `devin acp` process. ready closes once the
// gateway's initialize (+ authenticate) finished — initErr tells how.
type devinProc struct {
	hub   *Hub
	cmd   *exec.Cmd
	pid   int
	stdin io.Writer

	writeMu sync.Mutex
	// atomic: writeLine must read it without hub.mu (it already holds
	// writeMu; taking hub.mu there would invert the lock order).
	alive      atomic.Bool
	ready      chan struct{}
	draining   bool            // hub.mu: post-ready queue drain in progress
	initErr    error           // hub.mu; valid once ready is closed
	initResult json.RawMessage // cached `initialize` result for client handoffs
	authed     bool            // gateway (or a client) authenticated successfully
	queue      []queuedOp      // client frames waiting for ready/drain
}

type queuedOp struct {
	client *Client
	data   []byte // private copy — conn buffers are reused
	head   rpcHead
}

type Hub struct {
	mu            sync.Mutex
	proc          *devinProc
	sessions      map[string]*Session
	clients       map[*Client]bool
	pendingClient map[int64]*clientReq
	pendingAgent  map[int64]*agentReq
	nextID        int64
	clientSeq     int64
}

func newHub() *Hub {
	return &Hub{
		sessions:      map[string]*Session{},
		clients:       map[*Client]bool{},
		pendingClient: map[int64]*clientReq{},
		pendingAgent:  map[int64]*agentReq{},
	}
}

// ---------------------------------------------------------------- proc ---

// ensureProc returns the live resident process, spawning it on first demand
// or after a death. Lazy spawn keeps the bridge — and the test suite —
// hermetic until a client actually needs devin.
func (h *Hub) ensureProc() *devinProc {
	h.mu.Lock()
	defer h.mu.Unlock()
	if p := h.proc; p != nil && p.alive.Load() {
		return p
	}
	p := &devinProc{hub: h, ready: make(chan struct{})}
	p.alive.Store(true)
	h.proc = p
	go p.run()
	return p
}

func (p *devinProc) readyClosed() bool {
	select {
	case <-p.ready:
		return true
	default:
		return false
	}
}

// isReady reports whether initialize+authenticate finished successfully.
func (p *devinProc) isReady() bool {
	return p.readyClosed() && p.alive.Load() && p.initErr == nil
}

func (p *devinProc) writeLine(b []byte) error {
	p.writeMu.Lock()
	defer p.writeMu.Unlock()
	if !p.alive.Load() {
		return errors.New("devin process is down")
	}
	_, err := p.stdin.Write(append(b, '\n'))
	return err
}

// run spawns `devin acp`, then does the gateway's own initialize +
// authenticate so clients get cached answers instead of another handshake.
func (p *devinProc) run() {
	args := []string{"acp"}
	if *model != "" {
		args = append(args, "--model", *model)
	}
	cmd := exec.Command(*devinBin, args...)
	cmd.Dir = *defaultDir
	cmd.Stderr = &stderrLogger{}
	stdin, err := cmd.StdinPipe()
	if err != nil {
		p.failInit(fmt.Errorf("stdin pipe: %w", err))
		return
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		p.failInit(fmt.Errorf("stdout pipe: %w", err))
		return
	}
	if err := cmd.Start(); err != nil {
		p.failInit(fmt.Errorf("start %s acp: %w", *devinBin, err))
		return
	}
	p.cmd, p.stdin, p.pid = cmd, stdin, cmd.Process.Pid
	log.Printf("[%d] devin acp started (resident)", p.pid)
	go p.readLoop(stdout)
	go p.watch(cmd)

	if _, err := p.hub.callDevin(p, initReadyTimeout, "initialize", gatewayInitParams()); err != nil {
		p.failInit(fmt.Errorf("gateway initialize: %w", err))
		return
	}
	// authenticate: devin treats the ACP host as the only credential
	// source, so the gateway injects the same api key the old per-conn
	// relay did. Failure is NOT fatal — session/list etc. work unauthed and
	// a client `authenticate` is forwarded for the browser-login fallback.
	var initRes struct {
		AuthMethods []json.RawMessage `json:"authMethods"`
	}
	_ = json.Unmarshal(p.initResult, &initRes)
	apiKey := devinCredentialsKey()
	switch {
	case len(initRes.AuthMethods) == 0:
		p.authed = true
	case apiKey == "":
		log.Printf("[%d] no windsurf_api_key/credentials; leaving process unauthenticated (clients may authenticate)", p.pid)
	default:
		if _, err := p.hub.callDevin(p, initReadyTimeout, "authenticate", map[string]any{
			"methodId": "devin-browser",
			"_meta":    map[string]any{"api_key": apiKey},
		}); err != nil {
			log.Printf("[%d] gateway authenticate failed: %v (clients may retry via browser login)", p.pid, err)
		} else {
			p.authed = true
			log.Printf("[%d] devin auth injected", p.pid)
		}
	}
	p.markReady()
}

// gatewayInitParams mirrors the app's initialize params: declaring the
// revert capability is what makes devin echo the _cognition.ai/revert/*
// surface, and fs/terminal stay false so the agent never routes requests
// the app can't answer.
func gatewayInitParams() map[string]any {
	return map[string]any{
		"protocolVersion": 1,
		"clientCapabilities": map[string]any{
			"fs":       map[string]any{"readTextFile": false, "writeTextFile": false},
			"terminal": false,
		},
		"clientInfo": map[string]any{"name": "devingo-bridge", "version": gatewayVersion},
		"_meta":      map[string]any{"cognition.ai/revert": true},
	}
}

// markReady flips the process live and drains the frame queue in order.
// draining keeps later arrivals queueing behind the backlog — they can
// only bypass once it's fully dispatched (enqueue's paired check).
func (p *devinProc) markReady() {
	h := p.hub
	h.mu.Lock()
	p.setReadyLocked(nil)
	p.draining = true
	h.mu.Unlock()
	for {
		h.mu.Lock()
		ops := p.queue
		p.queue = nil
		if len(ops) == 0 {
			p.draining = false
			h.mu.Unlock()
			return
		}
		h.mu.Unlock()
		for _, op := range ops {
			h.dispatchReady(p, op.client, op.data, op.head)
		}
	}
}

// setReadyLocked closes the ready channel exactly once — every closer
// (markReady, failInit, procDied) goes through here under hub.mu.
func (p *devinProc) setReadyLocked(err error) {
	if p.readyClosed() {
		return
	}
	p.initErr = err
	close(p.ready)
}

func (p *devinProc) failInit(err error) {
	log.Printf("devin acp init failed: %v", err)
	p.hub.mu.Lock()
	p.setReadyLocked(err)
	p.hub.mu.Unlock()
	if p.cmd != nil {
		killProcessTree(p.cmd)
	}
	p.hub.procDied(p, err)
}

func (p *devinProc) readLoop(stdout io.Reader) {
	br := bufio.NewReaderSize(stdout, 1<<20)
	for {
		line, err := br.ReadBytes('\n')
		trimmed := strings.TrimRight(string(line), "\r\n")
		if trimmed != "" {
			p.hub.onDevinLine(p, []byte(trimmed))
		}
		if err != nil {
			return // watcher reports the exit
		}
	}
}

func (p *devinProc) watch(cmd *exec.Cmd) {
	err := cmd.Wait()
	if err != nil {
		p.hub.procDied(p, fmt.Errorf("devin acp exited: %w", err))
	} else {
		p.hub.procDied(p, errors.New("devin acp exited"))
	}
}

// procDied fails everything parked on the dead process and turns every
// hosted session non-running. Sessions and their journals stay — devin
// persists sessions agent-side, and the resident process respawns lazily on
// the next demand, where session/load re-hosts them.
func (h *Hub) procDied(p *devinProc, cause error) {
	h.mu.Lock()
	if !p.alive.Load() {
		h.mu.Unlock()
		return // already reaped (e.g. failInit raced the watcher)
	}
	p.alive.Store(false)
	if h.proc == p {
		h.proc = nil
	}
	queued := p.queue
	p.queue = nil
	log.Printf("devin acp process down (pid %d): %v", p.pid, cause)

	for id, cr := range h.pendingClient {
		if cr.proc != p {
			continue
		}
		delete(h.pendingClient, id)
		if cr.waiter != nil {
			cr.waiter <- callResult{err: cause}
		} else {
			h.deliverLocked(cr.client, errorFrame(cr.origID, -32000, "devin process died: "+cause.Error()))
		}
	}
	for id, ar := range h.pendingAgent {
		delete(h.pendingAgent, id)
		ar.timer.Stop()
		if ar.session != nil {
			delete(ar.session.pending, id)
			h.broadcastJournalLocked(ar.session, resolvedFrame(ar.session.id, ar.id, "agent_died"), nil)
		} else if ar.target != nil {
			// Unscoped requests have no journal to record the
			// resolution — tell the routed client directly.
			h.deliverLocked(ar.target, resolvedFrame("", ar.id, "agent_died"))
		}
	}
	p.setReadyLocked(cause) // no-op when ready already closed
	for _, s := range h.sessions {
		s.running = 0
		s.inflight = 0
		s.loaders = map[*Client]int{}
		h.broadcastJournalLocked(s, markerFrame("_devingo/agent_dead",
			map[string]any{"sessionId": s.id, "reason": cause.Error()}), nil)
		s.maybeArmIdleLocked(h)
	}
	h.mu.Unlock()

	// Queued-but-never-dispatched ops get a plain error, not a requeue —
	// blindly replaying them onto a fresh process could double-fire prompts.
	for _, op := range queued {
		if len(op.head.ID) > 0 {
			h.deliver(op.client, errorFrame(op.head.ID, -32000,
				"devin process restarting: "+cause.Error()))
		}
	}
}

// ------------------------------------------------------------ dispatch ---

// handleFrame is the client read loop's entry point. data is only borrowed
// for the duration of the call.
func (h *Hub) handleFrame(c *Client, data []byte) {
	head, ok := rpcParse(data)
	if !ok {
		// Never forward a malformed frame: devin would drop it while any
		// request it appeared to carry stays registered forever.
		log.Printf("client %s sent malformed frame; dropped: %s", c.id, truncStr(string(data), 120))
		return
	}
	h.dispatchFrame(c, data, head)
}

func (h *Hub) dispatchFrame(c *Client, data []byte, head rpcHead) {
	if head.Method == "" {
		// A response can only be answering an agent->client request.
		if len(head.ID) > 0 {
			h.clientResponse(c, data, head)
		}
		return
	}
	if strings.HasPrefix(head.Method, "_devingo/") {
		h.devingoMethod(c, data, head)
		return
	}
	p := h.procReady(c, data, head)
	if p == nil {
		return // queued until ready (or drained)
	}
	h.dispatchReady(p, c, data, head)
}

// dispatchReady handles a frame against a ready-closed process: cached
// initialize, ack-or-forward authenticate, forward everything else.
func (h *Hub) dispatchReady(p *devinProc, c *Client, data []byte, head rpcHead) {
	// initErr is written under hub.mu before ready closes; callers only
	// reach here after observing readyClosed() (or inside markReady's own
	// drain), which happens-after that write — no lock needed.
	if p.initErr != nil {
		if len(head.ID) > 0 {
			h.deliver(c, errorFrame(head.ID, -32000, "devin unavailable: "+p.initErr.Error()))
		}
		return
	}
	switch head.Method {
	case "initialize":
		// A notification-shaped initialize gets no reply — answering
		// anyway would emit a spurious {"id":null} response.
		if len(head.ID) > 0 {
			h.deliver(c, resultFrame(head.ID, p.initResult))
		}
	case "authenticate":
		h.mu.Lock()
		authed := p.authed
		h.mu.Unlock()
		if authed {
			// The resident process is already authenticated; ack instead
			// of dragging the client through a browser login.
			if len(head.ID) > 0 {
				h.deliver(c, resultFrame(head.ID, json.RawMessage(`{}`)))
			}
			return
		}
		h.forwardToDevin(c, p, head, data) // browser-login fallback
	default:
		h.forwardToDevin(c, p, head, data)
	}
}

// procReady resolves the resident process for a devin-bound frame. It
// returns the process once ready has closed AND the post-ready drain has
// flushed (initErr tells how init went), nil when the frame was queued
// behind the handshake/backlog or an error was delivered after
// back-to-back dead processes.
func (h *Hub) procReady(c *Client, data []byte, head rpcHead) *devinProc {
	for attempts := 0; attempts < 2; attempts++ {
		p := h.ensureProc()
		h.mu.Lock()
		if !p.alive.Load() {
			h.mu.Unlock()
			continue // died between ensureProc and the check — retry on a fresh one
		}
		// readyClosed alone is not dispatchable: while markReady is still
		// draining, a direct dispatch would let this frame overtake ops
		// already queued for devin's stdin. The drain loop picks up
		// anything appended here under the same lock.
		if p.readyClosed() && !p.draining {
			h.mu.Unlock()
			return p
		}
		p.queue = append(p.queue, queuedOp{
			client: c, head: head,
			data: append([]byte(nil), data...), // private copy — conn buffers are reused
		})
		h.mu.Unlock()
		return nil
	}
	// Two processes died mid-check; the binary is probably missing.
	if len(head.ID) > 0 {
		h.deliver(c, errorFrame(head.ID, -32000, "devin process unavailable"))
	}
	return nil
}

// forwardToDevin rewrites the request id onto the internal sequence,
// registers it for the response, applies session bookkeeping and writes it.
// Client notifications (no id) get no response — they forward verbatim.
func (h *Hub) forwardToDevin(c *Client, p *devinProc, head rpcHead, data []byte) {
	if len(head.ID) == 0 {
		if err := p.writeLine(data); err != nil {
			log.Printf("forward notification %s: %v", head.Method, err)
		}
		return
	}
	h.mu.Lock()
	internal := h.nextID + 1
	h.nextID = internal
	cr := &clientReq{proc: p, client: c, origID: head.ID, method: head.Method, sid: head.Params.SessionID, cwd: head.Params.Cwd}
	h.pendingClient[internal] = cr
	var s *Session
	if cr.sid != "" {
		s = h.getSessionLocked(cr.sid)
		cr.session = s
		s.inflight++
		// Any in-flight session-scoped request — not just prompt — keeps
		// the session alive: disarm an armed idle timer for the flight's
		// duration (the response re-arms via maybeArmIdleLocked).
		s.cancelIdleLocked()
		switch head.Method {
		case "session/prompt":
			s.running++
			s.lastPrompter = c
		case "session/load":
			s.loaders[c]++
		}
	}
	h.mu.Unlock()

	wire := rewriteID(data, rawInt(internal))
	if err := p.writeLine(wire); err != nil {
		h.mu.Lock()
		delete(h.pendingClient, internal)
		if s != nil {
			s.inflight--
			switch head.Method {
			case "session/prompt":
				s.running--
				if s.lastPrompter == c {
					s.lastPrompter = nil
				}
			case "session/load":
				s.loaders[c]--
				if s.loaders[c] == 0 {
					delete(s.loaders, c)
				}
			}
			// Whatever state the request claimed is rolled back above; a
			// lazily-created session (foreign sid) must not leak unarmed.
			s.maybeArmIdleLocked(h)
		}
		h.mu.Unlock()
		h.deliver(c, errorFrame(head.ID, -32000, "write to devin: "+err.Error()))
	}
}

// clientResponse answers a pending agent->client request; first reply wins.
func (h *Hub) clientResponse(c *Client, data []byte, head rpcHead) {
	n, ok := parseInternalID(head.ID)
	if !ok {
		return
	}
	h.mu.Lock()
	ar := h.pendingAgent[n]
	if ar != nil {
		switch {
		case ar.session != nil && !ar.session.subs[c] && ar.session.loaders[c] == 0:
			// Internal ids are sequential and guessable: a client outside
			// the session's audience (subscribers + loaders — the same
			// pool agentReqTargetsLocked routes to) must not resolve the
			// request or consume it from the real audience.
			log.Printf("client %s answered foreign session request %d; dropped", c.id, n)
			ar = nil
		case ar.session == nil && ar.target != nil && ar.target != c:
			// Unscoped requests route to exactly one client; only it may
			// answer.
			log.Printf("client %s answered unscoped request %d routed to another client; dropped", c.id, n)
			ar = nil
		}
	}
	if ar != nil {
		delete(h.pendingAgent, n)
		if ar.session != nil {
			delete(ar.session.pending, n)
		}
		ar.timer.Stop()
	}
	h.mu.Unlock()
	if ar == nil {
		return // already resolved, timed out, or not this client's to answer
	}
	h.forwardResponseToDevin(ar, rewriteID(data, ar.devinID))
	if ar.session == nil {
		return
	}
	h.mu.Lock()
	h.broadcastJournalLocked(ar.session, resolvedFrame(ar.session.id, ar.id, responseOutcome(data)), c)
	ar.session.maybeArmIdleLocked(h)
	h.mu.Unlock()
}

// responseOutcome extracts result.outcome.outcome from a permission answer
// (falls back to "answered"/"error").
func responseOutcome(data []byte) string {
	var f struct {
		Result struct {
			Outcome struct {
				Outcome string `json:"outcome"`
			} `json:"outcome"`
		} `json:"result"`
		Error json.RawMessage `json:"error"`
	}
	if json.Unmarshal(data, &f) != nil {
		return "answered"
	}
	if len(f.Error) > 0 && string(f.Error) != "null" {
		return "error"
	}
	if f.Result.Outcome.Outcome != "" {
		return f.Result.Outcome.Outcome
	}
	return "answered"
}

func (h *Hub) forwardResponseToDevin(ar *agentReq, wire []byte) {
	h.mu.Lock()
	p := h.proc
	alive := p != nil && p.alive.Load()
	h.mu.Unlock()
	if !alive {
		return
	}
	if err := p.writeLine(wire); err != nil {
		log.Printf("forward response for agent req %d: %v", ar.id, err)
	}
}

// ------------------------------------------------------- devin -> hub ----

func (h *Hub) onDevinLine(p *devinProc, line []byte) {
	head, ok := rpcParse(line)
	if !ok {
		log.Printf("devin emitted malformed frame; dropped: %s", truncStr(string(line), 120))
		return
	}
	if head.Method == "" {
		if len(head.ID) > 0 {
			h.devinResponse(p, line, head)
		}
		return
	}
	if len(head.ID) > 0 {
		h.devinRequest(p, line, head)
		return
	}
	h.devinNotification(p, line, head)
}

// devinResponse routes a response back to the requesting client (or the
// gateway's internal caller), restoring the original id and journaling
// session-scoped results.
func (h *Hub) devinResponse(p *devinProc, line []byte, head rpcHead) {
	n, ok := parseInternalID(head.ID)
	if !ok {
		log.Printf("devin response with foreign id %s dropped", truncStr(string(head.ID), 60))
		return
	}
	h.mu.Lock()
	cr := h.pendingClient[n]
	delete(h.pendingClient, n)
	h.mu.Unlock()
	if cr == nil {
		return // client gave up / timed out
	}
	if cr.waiter != nil {
		cr.waiter <- callResult{frame: append([]byte(nil), line...)}
		return
	}

	failed := len(head.Error) > 0 && string(head.Error) != "null"
	sid := cr.sid
	if cr.method == "session/new" && !failed {
		var res struct {
			SessionID string `json:"sessionId"`
		}
		_ = json.Unmarshal(head.Result, &res)
		sid = res.SessionID
	}

	raw := rewriteID(line, cr.origID) // frame as the client should see it
	h.mu.Lock()
	defer h.mu.Unlock()
	// A client-driven authenticate that succeeded upgrades the whole
	// process — subsequent client authenticates get the fast ack.
	if cr.method == "authenticate" && !failed {
		cr.proc.authed = true
	}
	var s *Session
	if cr.method == "session/new" && !failed && sid != "" {
		s = h.getSessionLocked(sid) // the session's id only exists now
	} else if sid != "" {
		s = h.sessions[sid]
	}
	// Bookkeeping pairs with the forward-time increments on the session
	// object that was marked — h.sessions[sid] may be a different (or
	// gone) session after a delete+lazy-recreate edge.
	if cr.session != nil {
		cr.session.inflight--
		if cr.method == "session/prompt" {
			cr.session.running--
			if cr.session.lastPrompter == cr.client {
				cr.session.lastPrompter = nil
			}
			// Offline push: a turn that just ended with no live audience
			// still reaches the user. s == cr.session confines this to the
			// still-hosted session (a deleted mid-turn one drops out).
			if cr.session.running == 0 && s == cr.session && !s.hasAudienceLocked() {
				bridgePush.notify("任务完成", false)
			}
		}
	}
	if s == nil {
		h.deliverLocked(cr.client, raw)
		return
	}
	switch cr.method {
	case "session/new":
		if !failed {
			h.subscribeLocked(cr.client, s)
			if cr.cwd != "" {
				s.cwd = cr.cwd
			}
			s.lastInfo = append(json.RawMessage(nil), head.Result...)
		}
	case "session/load":
		wasLoader := cr.session.loaders[cr.client] > 0
		if wasLoader {
			cr.session.loaders[cr.client]--
			if cr.session.loaders[cr.client] == 0 {
				delete(cr.session.loaders, cr.client)
			}
		}
		if !failed {
			if wasLoader {
				// A client that detached (or died) mid-load forfeited the
				// subscription — re-adding it would resurrect a detach.
				h.subscribeLocked(cr.client, s)
			}
			if cr.cwd != "" {
				s.cwd = cr.cwd
			}
			s.lastInfo = append(json.RawMessage(nil), head.Result...)
		}
		if len(s.loaders) == 0 {
			// The delivery pool flips back to subscribers — but everything
			// journaled during the load window was pushed to loaders only.
			// Replay and live traffic are indistinguishable inside the
			// window, so genuinely-new events may have been withheld from
			// the other subscribers: tell them to re-attach with their
			// lastSeq, which replays the journaled gap. Fires on success
			// and failure alike; journaled so a kicked/reconnecting
			// subscriber sees the same hint. The loader itself is excluded
			// — it already holds the full replay.
			h.broadcastJournalLocked(s, markerFrame("_devingo/session_resync",
				map[string]any{"sessionId": s.id}), cr.client)
		}
	case "session/delete":
		if !failed {
			// Other subscribers learn the session went away — symmetric
			// with session_resync/agent_dead so they can drop local state.
			h.broadcastJournalLocked(s, markerFrame("_devingo/session_deleted",
				map[string]any{"sessionId": s.id}), cr.client)
			h.dropSessionLocked(s, false) // devin already deleted it
			s = nil
		}
	}
	if s == nil {
		h.deliverLocked(cr.client, raw)
		return
	}
	entry := h.appendLocked(s, jResp, raw)
	h.deliverLocked(cr.client, withSeq(entry))
	// Other subscribers learn the turn state changed via the wrapped copy.
	if wrapped := wrapEventFrame(s.id, entry); len(wrapped) > 0 {
		for sub := range s.subs {
			if sub != cr.client {
				h.deliverLocked(sub, wrapped)
			}
		}
	}
	s.maybeArmIdleLocked(h)
}

// devinRequest handles an agent->client request: its id is rewritten onto
// the internal sequence (agent ids could collide with ours), it is journaled
// when session-scoped, then routed — permission prompts broadcast to every
// subscriber (first reply wins), other request kinds go to a single client.
func (h *Hub) devinRequest(p *devinProc, line []byte, head rpcHead) {
	sid := head.Params.SessionID
	h.mu.Lock()
	if !p.alive.Load() {
		// Buffered stdout can still feed lines after procDied ran; a
		// request registered now would never resolve — nobody is left to
		// answer it and its timer/journal would leak onto a corpse.
		// (Notifications keep flowing; they're harmless last words.)
		h.mu.Unlock()
		return
	}
	internal := h.nextID + 1
	h.nextID = internal
	ar := &agentReq{
		id:      internal,
		devinID: append(json.RawMessage(nil), head.ID...),
	}
	h.pendingAgent[internal] = ar
	wire := rewriteID(line, rawInt(internal))
	ar.frame = append(json.RawMessage(nil), wire...)

	var s *Session
	var targets []*Client
	if sid != "" {
		s = h.getSessionLocked(sid)
		ar.session = s
		s.pending[internal] = ar
		entry := h.appendLocked(s, jReq, wire)
		ar.seq = entry.seq
		targets = h.agentReqTargetsLocked(s, head.Method)
	} else {
		for c := range h.clients {
			if !c.dead {
				ar.target = c // unscoped: routed to exactly one answerer
				targets = []*Client{c}
				break
			}
		}
	}
	// Permissions stay pending across zero subscribers — that's the
	// lockscreen-resume case. permTTL bounds them either way. The timer is
	// armed under the lock so no resolve path can Stop a nil timer.
	ar.timer = time.AfterFunc(*permTTL, func() {
		res, _ := json.Marshal(map[string]any{
			"outcome": map[string]any{"outcome": "cancelled"},
		})
		h.resolveAgentReq(ar, "timeout", resultFrameRaw(ar.devinID, res))
	})
	noAnswer := len(targets) == 0 && head.Method != "session/request_permission"
	// Offline push: the FIRST permission arriving with no live audience is
	// worth paging the phone for — later ones ride the same ping
	// (len(s.pending)==1 means the request just registered is the only one).
	pushPerm := head.Method == "session/request_permission" && len(targets) == 0 &&
		(s == nil || len(s.pending) == 1)
	h.mu.Unlock()

	if pushPerm {
		bridgePush.notify("有新审批", true)
	}
	if noAnswer {
		// Nothing to hang: nobody can answer fs/terminal-style calls.
		h.resolveAgentReq(ar, "no_client",
			errorFrame(ar.devinID, -32000, "no client connected"))
		return
	}
	if s == nil {
		for _, c := range targets {
			h.deliver(c, wire)
		}
		return
	}
	wireOut := withSeq(journalEntry{seq: ar.seq, frame: ar.frame})
	for _, c := range targets {
		h.deliver(c, wireOut)
	}
}

// agentReqTargetsLocked picks the audience for an agent->client request.
// During a session/load window everything routes to the loader alone (the
// traffic is replay addressed to them).
func (h *Hub) agentReqTargetsLocked(s *Session, method string) []*Client {
	pool := s.subs
	if len(s.loaders) > 0 {
		pool = make(map[*Client]bool, len(s.loaders))
		for c := range s.loaders {
			pool[c] = true
		}
	}
	if len(pool) == 0 {
		return nil
	}
	if method == "session/request_permission" {
		out := make([]*Client, 0, len(pool))
		for c := range pool {
			if !c.dead {
				out = append(out, c)
			}
		}
		return out
	}
	// Point-to-point request: prefer the session's prompter.
	if s.lastPrompter != nil && pool[s.lastPrompter] && !s.lastPrompter.dead {
		return []*Client{s.lastPrompter}
	}
	for c := range pool {
		if !c.dead {
			return []*Client{c}
		}
	}
	return nil
}

// hasAudienceLocked reports whether any live client can see this session's
// traffic right now — the same pool agentReqTargetsLocked routes to:
// subscribers, plus loaders during a load window.
func (s *Session) hasAudienceLocked() bool {
	for c := range s.subs {
		if !c.dead {
			return true
		}
	}
	for c := range s.loaders {
		if !c.dead {
			return true
		}
	}
	return false
}

// devinNotification journals a session-scoped notification and fans it out;
// unscoped ones broadcast to every client and are not journaled.
func (h *Hub) devinNotification(p *devinProc, line []byte, head rpcHead) {
	sid := head.Params.SessionID
	h.mu.Lock()
	if sid == "" {
		for c := range h.clients {
			h.deliverLocked(c, line)
		}
		h.mu.Unlock()
		return
	}
	s := h.getSessionLocked(sid)
	entry := h.appendLocked(s, jNotif, line)
	wire := withSeq(entry)
	pool := s.subs
	if len(s.loaders) > 0 {
		pool = make(map[*Client]bool, len(s.loaders))
		for c := range s.loaders {
			pool[c] = true
		}
	}
	for c := range pool {
		h.deliverLocked(c, wire)
	}
	// A lazily-created session (foreign sid) has nobody to keep it alive —
	// arm the idle grace so it doesn't linger forever. For hosted sessions
	// this is a no-op (not idle, or timer already armed).
	s.maybeArmIdleLocked(h)
	h.mu.Unlock()
}

// ----------------------------------------------------------- _devingo ----

// devingoMethod serves gateway-private requests. Notifications (no id) are
// dropped — every _devingo/* method is a request.
func (h *Hub) devingoMethod(c *Client, data []byte, head rpcHead) {
	if len(head.ID) == 0 {
		return
	}
	var params struct {
		ClientID  string `json:"clientId"`
		SessionID string `json:"sessionId"`
		AfterSeq  uint64 `json:"afterSeq"`
		Cwd       string `json:"cwd"`
	}
	var raw struct {
		Params json.RawMessage `json:"params"`
	}
	if json.Unmarshal(data, &raw) == nil && len(raw.Params) > 0 {
		_ = json.Unmarshal(raw.Params, &params)
	}
	switch head.Method {
	case "_devingo/hello":
		h.mu.Lock()
		if params.ClientID != "" {
			c.id = params.ClientID
		}
		clientID := c.id
		h.mu.Unlock()
		res, _ := json.Marshal(map[string]any{
			"clientId": clientID,
			"version":  gatewayVersion,
			"capabilities": map[string]any{
				"attach":             true,
				"sessions":           true,
				"journal":            true,
				"permissionResolved": true,
				"sessionResync":      true,
				"initCached":         true,
			},
		})
		h.deliver(c, resultFrameRaw(head.ID, res))
	case "_devingo/sessions":
		h.mu.Lock()
		if c.listBusy {
			// One call at a time per client; only the newest waiter is
			// kept — the result is a fresh snapshot either way, so older
			// queued calls are superseded, not queued behind it.
			c.listNext = &sessionListQuery{
				id:  append(json.RawMessage(nil), head.ID...),
				cwd: params.Cwd,
			}
			h.mu.Unlock()
			return
		}
		c.listBusy = true
		h.mu.Unlock()
		go h.devingoSessions(c, head.ID, params.Cwd)
	case "_devingo/attach":
		h.attach(c, head.ID, params.SessionID, params.AfterSeq, params.Cwd)
	case "_devingo/detach":
		h.detach(c, head.ID, params.SessionID)
	default:
		h.deliver(c, errorFrame(head.ID, -32601, "unknown method "+head.Method))
	}
}

// devingoSessions merges devin's session/list with live hub state. It runs
// off the read loop because callDevin blocks on the agent. At most one
// call is in flight per client; a queued next call launches as this one
// finishes.
func (h *Hub) devingoSessions(c *Client, id json.RawMessage, cwd string) {
	defer func() {
		h.mu.Lock()
		next := c.listNext
		c.listNext = nil
		if c.dead {
			next = nil
		}
		c.listBusy = next != nil
		h.mu.Unlock()
		if next != nil {
			go h.devingoSessions(c, next.id, next.cwd)
		}
	}()
	result, err := h.callSessionList(cwd)
	if err != nil {
		h.deliver(c, errorFrame(id, -32000, "session/list: "+err.Error()))
		return
	}
	var parsed struct {
		Sessions []map[string]any `json:"sessions"`
	}
	if json.Unmarshal(result, &parsed) != nil {
		h.deliver(c, resultFrame(id, result))
		return
	}
	if parsed.Sessions == nil {
		parsed.Sessions = []map[string]any{}
	}
	h.mu.Lock()
	seen := map[string]bool{}
	for _, sm := range parsed.Sessions {
		sid, _ := sm["sessionId"].(string)
		if s := h.sessions[sid]; s != nil {
			seen[sid] = true
			sm["running"] = s.running > 0
			sm["pendingPermission"] = len(s.pending) > 0
		}
	}
	// Hosted sessions devin somehow doesn't list (e.g. brand-new).
	for sid, s := range h.sessions {
		if !seen[sid] {
			parsed.Sessions = append(parsed.Sessions, map[string]any{
				"sessionId":         sid,
				"running":           s.running > 0,
				"pendingPermission": len(s.pending) > 0,
			})
		}
	}
	h.mu.Unlock()
	out, _ := json.Marshal(map[string]any{"sessions": parsed.Sessions})
	h.deliver(c, resultFrameRaw(id, out))
}

// attach subscribes the client and replays journal events past afterSeq,
// then re-sends still-pending approvals the replay didn't already cover.
// needLoad tells the client to fall back to session/load.
func (h *Hub) attach(c *Client, id json.RawMessage, sid string, afterSeq uint64, cwd string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	s := h.sessions[sid]
	if s == nil {
		h.deliverLocked(c, resultFrameRaw(id, mustJSON(map[string]any{
			"sessionId": sid, "needLoad": true,
		})))
		return
	}
	// The client's cursor is unservable two ways: the journal no longer
	// reaches back that far (afterSeq==0 counts too once the ring has been
	// cut — only a still-intact journal, earliestSeq == 1, may replay-all),
	// or the cursor sits PAST the high-water mark. afterSeq >= nextSeq is
	// unreachable within one epoch — every seq the client saw was journaled
	// here — so it can only reference a dead journal: the session was
	// evicted and lazily re-created (nextSeq restarted at 1), or the whole
	// bridge restarted. Replaying nothing would diverge silently AND pin
	// the client's live floor above the new epoch's seqs.
	if s.earliestSeq() > afterSeq+1 || afterSeq >= s.nextSeq {
		h.deliverLocked(c, resultFrameRaw(id, mustJSON(map[string]any{
			"sessionId": sid, "needLoad": true, "lastSeq": s.nextSeq - 1,
		})))
		return
	}
	h.subscribeLocked(c, s)
	// A session the hub re-created lazily (journal eviction, proc restart)
	// lost its recorded workspace — the client's hint re-homes /fs/write.
	// A recorded cwd stays authoritative; the hint only fills a gap.
	// It's trusted client input — a token holder can already pick any
	// cwd via session/new, so honoring it here widens nothing.
	if s.cwd == "" && cwd != "" {
		s.cwd = cwd
	}
	res := map[string]any{
		"sessionId":         sid,
		"needLoad":          false,
		"running":           s.running > 0,
		"lastSeq":           s.nextSeq - 1,
		"pendingPermission": len(s.pending) > 0,
	}
	if len(s.lastInfo) > 0 {
		res["session"] = s.lastInfo
	}
	h.deliverLocked(c, resultFrameRaw(id, mustJSON(res)))
	// Replay under the same lock so live events queue behind the backlog.
	for _, e := range s.journal {
		if e.seq <= afterSeq {
			continue
		}
		h.deliverLocked(c, h.replayWireLocked(s, e))
	}
	// Still-open approvals the replay just covered are in the backlog
	// already; the rest (older than afterSeq, or evicted from the ring)
	// are re-sent from the request's own frame copy. They go WITHOUT a
	// _seq: these are re-deliveries keyed by request id, not new journal
	// events — a stale/low _seq would trip seq-dedup on the client and
	// silently swallow a live permission.
	for _, ar := range s.pending {
		if ar.seq <= afterSeq || !s.hasEntry(ar.seq) {
			h.deliverLocked(c, ar.frame)
		}
	}
}

func (h *Hub) detach(c *Client, id json.RawMessage, sid string) {
	h.mu.Lock()
	if s := h.sessions[sid]; s != nil {
		h.unsubscribeLocked(c, s)
	}
	h.mu.Unlock()
	h.deliver(c, resultFrameRaw(id, json.RawMessage(`{}`)))
}

// replayWireLocked renders a journal entry for a re-attaching client:
// request/notification frames verbatim with _seq, client-request results
// wrapped as _devingo/event (their ids belong to other connections).
func (h *Hub) replayWireLocked(s *Session, e journalEntry) []byte {
	if e.kind == jResp {
		return wrapEventFrame(s.id, e)
	}
	return withSeq(e)
}

// ------------------------------------------------------------ sessions ---

// getSessionLocked finds or lazily creates the session for an id. Lazily
// covers journal-worthy traffic for sessions the hub never saw created
// (foreign sids in devin's stream).
func (h *Hub) getSessionLocked(sid string) *Session {
	if s := h.sessions[sid]; s != nil {
		return s
	}
	if len(h.sessions) >= maxHostedSessions {
		h.evictOldestIdleLocked()
	}
	s := &Session{
		id:        sid,
		subs:      map[*Client]bool{},
		loaders:   map[*Client]int{},
		pending:   map[int64]*agentReq{},
		nextSeq:   1,
		idleSince: time.Now(),
	}
	h.sessions[sid] = s
	return s
}

func (s *Session) earliestSeq() uint64 {
	if len(s.journal) == 0 {
		return s.nextSeq // nothing journaled yet
	}
	return s.journal[0].seq
}

func (s *Session) hasEntry(seq uint64) bool {
	if len(s.journal) == 0 {
		return false
	}
	i := int(seq - s.journal[0].seq)
	return i >= 0 && i < len(s.journal)
}

// appendLocked journals one frame, evicting the oldest entries past the
// ring caps. Pending approvals keep their own copy in agentReq.frame, so
// eviction never strands an unanswered request.
func (h *Hub) appendLocked(s *Session, kind byte, frame json.RawMessage) journalEntry {
	e := journalEntry{seq: s.nextSeq, kind: kind,
		frame: append(json.RawMessage(nil), frame...)}
	s.nextSeq++
	s.journal = append(s.journal, e)
	s.jBytes += len(e.frame)
	for len(s.journal) > 0 &&
		(len(s.journal) > journalMaxEntries || s.jBytes > journalMaxBytes) {
		s.jBytes -= len(s.journal[0].frame)
		// Zero the slot before reslicing: the backing array stays
		// allocated, so an un-cleared entry would keep its frame
		// reachable (doubling the intended byte bound).
		s.journal[0] = journalEntry{}
		s.journal = s.journal[1:]
	}
	return e
}

// broadcastJournalLocked stores a gateway-synthesized notification and
// pushes it live to subscribers (skipping `except`, e.g. the responder that
// just resolved the approval). Used for permission_resolved / agent_dead.
func (h *Hub) broadcastJournalLocked(s *Session, frame json.RawMessage, except *Client) {
	entry := h.appendLocked(s, jNotif, frame)
	wire := withSeq(entry)
	for c := range s.subs {
		if c != except {
			h.deliverLocked(c, wire)
		}
	}
	// Loaders too: during a load window they own the delivery pool, and a
	// loader must still see live permission_resolved/agent_dead markers.
	for c := range s.loaders {
		if c != except && !s.subs[c] {
			h.deliverLocked(c, wire)
		}
	}
}

func (h *Hub) subscribeLocked(c *Client, s *Session) {
	if c == nil || c.dead {
		return
	}
	s.subs[c] = true
	c.sessions[s] = true
	s.cancelIdleLocked()
}

func (h *Hub) unsubscribeLocked(c *Client, s *Session) {
	delete(s.subs, c)
	delete(s.loaders, c)
	delete(c.sessions, s)
	if s.lastPrompter == c {
		s.lastPrompter = nil
	}
	s.maybeArmIdleLocked(h)
}

// dropSessionLocked removes the session from the hub. With sendClose the
// agent-side lock is released via session/close (fire-and-forget).
func (h *Hub) dropSessionLocked(s *Session, sendClose bool) {
	delete(h.sessions, s.id)
	if s.idleTimer != nil {
		s.idleTimer.Stop()
		s.idleTimer = nil
	}
	for _, ar := range s.pending {
		delete(h.pendingAgent, ar.id)
		ar.timer.Stop()
	}
	for c := range s.subs {
		delete(c.sessions, s)
	}
	if sendClose {
		if p := h.proc; p != nil && p.alive.Load() && p.isReady() {
			go h.callDevin(p, devinCallTimeout, "session/close",
				map[string]any{"sessionId": s.id})
		}
	}
}

// maybeArmIdleLocked schedules the idle grace timer once a session holds
// nothing worth keeping: no subscribers, no running turn, no pending
// approvals. Any of those reappearing cancels it.
func (s *Session) maybeArmIdleLocked(h *Hub) {
	s.maybeArmIdleTTL(h, *idleTTL)
}

func (s *Session) maybeArmIdleTTL(h *Hub, ttl time.Duration) {
	idle := len(s.subs) == 0 && s.inflight == 0 && len(s.pending) == 0 && len(s.loaders) == 0
	if !idle {
		s.cancelIdleLocked()
		return
	}
	if s.idleTimer != nil {
		return // already counting down
	}
	s.idleSince = time.Now()
	s.idleTimer = time.AfterFunc(ttl, func() {
		h.mu.Lock()
		defer h.mu.Unlock()
		s.idleTimer = nil
		if len(s.subs) == 0 && s.inflight == 0 && len(s.pending) == 0 && len(s.loaders) == 0 {
			log.Printf("session %s idle past %s; evicting", s.id, *idleTTL)
			h.dropSessionLocked(s, true)
		}
	})
}

func (s *Session) cancelIdleLocked() {
	if s.idleTimer != nil {
		s.idleTimer.Stop()
		s.idleTimer = nil
	}
}

// evictOldestIdleLocked drops the stalest fully-idle session when the
// hosted-session cap is hit. All-busy just goes one over the cap.
func (h *Hub) evictOldestIdleLocked() {
	var oldest *Session
	for _, s := range h.sessions {
		if len(s.subs) > 0 || s.inflight > 0 || len(s.pending) > 0 || len(s.loaders) > 0 {
			continue
		}
		if oldest == nil || s.idleSince.Before(oldest.idleSince) {
			oldest = s
		}
	}
	if oldest != nil {
		log.Printf("session cap %d reached; evicting idle session %s", maxHostedSessions, oldest.id)
		h.dropSessionLocked(oldest, true)
	}
}

// resolveAgentReq finishes a pending agent->client request: forwards the
// wire (a response or error) to devin when given, then journals + tells the
// room it resolved.
func (h *Hub) resolveAgentReq(ar *agentReq, outcome string, toDevin []byte) {
	h.mu.Lock()
	if h.pendingAgent[ar.id] != ar {
		h.mu.Unlock()
		return // another path won
	}
	delete(h.pendingAgent, ar.id)
	if ar.session != nil {
		delete(ar.session.pending, ar.id)
	}
	ar.timer.Stop()
	h.mu.Unlock()
	if len(toDevin) > 0 {
		h.forwardResponseToDevin(ar, toDevin)
	}
	if ar.session == nil {
		// Unscoped requests aren't journaled, but the routed client still
		// holds the request open — tell it the resolution happened.
		if ar.target != nil {
			h.deliver(ar.target, resolvedFrame("", ar.id, outcome))
		}
		return
	}
	h.mu.Lock()
	h.broadcastJournalLocked(ar.session, resolvedFrame(ar.session.id, ar.id, outcome), nil)
	ar.session.maybeArmIdleLocked(h)
	h.mu.Unlock()
}

// ------------------------------------------------------------ delivery ---

// deliver enqueues a frame for one client. A full queue means the consumer
// can't keep up — it gets kicked and must re-attach (the journal has what
// it missed).
func (h *Hub) deliver(c *Client, wire []byte) {
	h.mu.Lock()
	h.deliverLocked(c, wire)
	h.mu.Unlock()
}

func (h *Hub) deliverLocked(c *Client, wire []byte) {
	if c == nil || c.dead {
		return
	}
	select {
	case c.send <- wire:
	default:
		log.Printf("client %s send queue full; kicking", c.id)
		// Mark dead under the lock so no other locked path can send after
		// the channel is closed, then let the conn die asynchronously.
		c.dead = true
		close(c.send)
		go c.kick(1013, "send queue overflow; re-attach to replay")
	}
}

// removeClient detaches a dead connection: subscriptions come off their
// sessions (arming idle timers) while in-flight requests stay registered —
// their responses still mark turn state and journal correctly. reapTTL > 0
// overrides the idle grace (oversized-frame orphan reap).
func (h *Hub) removeClient(c *Client, reapTTL time.Duration) {
	h.mu.Lock()
	if !c.dead {
		c.dead = true
		close(c.send)
	}
	// A kicked client arrives with dead already true — the teardown below
	// still has to run: subscriptions, loaders and membership all detach.
	delete(h.clients, c)
	for s := range c.sessions {
		delete(s.subs, c)
		delete(s.loaders, c)
		if s.lastPrompter == c {
			s.lastPrompter = nil
		}
		if reapTTL > 0 {
			s.maybeArmIdleTTL(h, reapTTL)
		} else {
			s.maybeArmIdleLocked(h)
		}
	}
	c.sessions = map[*Session]bool{}
	for _, cr := range h.pendingClient {
		if cr.client == c {
			cr.client = nil
		}
	}
	h.mu.Unlock()
}

func (h *Hub) addClient(c *Client) {
	h.mu.Lock()
	h.clientSeq++
	if c.id == "" {
		c.id = "c-" + strconv.FormatInt(h.clientSeq, 10)
	}
	h.clients[c] = true
	h.mu.Unlock()
}

// -------------------------------------------------------------- calls ----

// callDevin issues a gateway-internal request and waits for the response
// frame (or ctx timeout). The waiter rides the same pendingClient map as
// client traffic.
func (h *Hub) callDevin(p *devinProc, timeout time.Duration, method string, params any) (json.RawMessage, error) {
	h.mu.Lock()
	internal := h.nextID + 1
	h.nextID = internal
	cr := &clientReq{proc: p, waiter: make(chan callResult, 1), method: method}
	h.pendingClient[internal] = cr
	h.mu.Unlock()

	msg := map[string]any{"jsonrpc": "2.0", "id": internal, "method": method}
	if params != nil {
		msg["params"] = params
	}
	wire, _ := json.Marshal(msg)
	if err := p.writeLine(wire); err != nil {
		h.mu.Lock()
		delete(h.pendingClient, internal)
		h.mu.Unlock()
		return nil, err
	}
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case r := <-cr.waiter:
		if r.err != nil {
			return nil, r.err
		}
		var head rpcHead
		_ = json.Unmarshal(r.frame, &head)
		if len(head.Error) > 0 && string(head.Error) != "null" {
			var e struct {
				Code    int    `json:"code"`
				Message string `json:"message"`
			}
			_ = json.Unmarshal(head.Error, &e)
			return nil, fmt.Errorf("%s (code %d)", e.Message, e.Code)
		}
		if method == "initialize" {
			p.initResult = append(json.RawMessage(nil), head.Result...)
		}
		return head.Result, nil
	case <-timer.C:
		h.mu.Lock()
		delete(h.pendingClient, internal)
		h.mu.Unlock()
		return nil, fmt.Errorf("%s: no response within %s", method, timeout)
	}
}

// callSessionList backs _devingo/sessions — always over the resident process.
// A failed first handshake buys exactly one respawn; when that proc comes
// up healthy the list must run on it — falling off the loop would report
// "unavailable" while a perfectly good process sits idle.
func (h *Hub) callSessionList(cwd string) (json.RawMessage, error) {
	for attempts := 0; attempts < 2; attempts++ {
		p := h.ensureProc()
		if !p.readyClosed() {
			// Wait for the handshake instead of erroring: a session list
			// is exactly the cold-start demand.
			t := time.NewTimer(initReadyTimeout)
			select {
			case <-p.ready:
				t.Stop()
			case <-t.C:
				return nil, errors.New("devin process did not become ready")
			}
		}
		h.mu.Lock()
		initErr := p.initErr
		h.mu.Unlock()
		if initErr != nil {
			if attempts == 0 {
				continue // spawn failed once; respawn and retry
			}
			return nil, initErr
		}
		var params map[string]any
		if cwd != "" {
			params = map[string]any{"cwd": cwd}
		}
		return h.callDevin(p, devinCallTimeout, "session/list", params)
	}
	return nil, errors.New("devin process unavailable")
}

// ------------------------------------------------------------- frames ----

// withSeq splices the top-level "_seq" field into a journaled frame. The
// frame is a JSON object by construction.
func withSeq(e journalEntry) []byte {
	f := e.frame
	if len(f) == 0 || f[0] != '{' {
		return f
	}
	out := make([]byte, 0, len(f)+24)
	out = append(out, '{')
	out = append(out, []byte(`"_seq":`+strconv.FormatUint(e.seq, 10)+`,`)...)
	return append(out, f[1:]...)
}

// wrapEventFrame renders a journaled client-request result for
// non-requester audiences as a _devingo/event notification.
func wrapEventFrame(sid string, e journalEntry) []byte {
	params, err := json.Marshal(map[string]any{
		"sessionId": sid,
		"event":     e.frame,
	})
	if err != nil {
		return nil
	}
	frame, err := json.Marshal(map[string]any{
		"jsonrpc": "2.0",
		"method":  "_devingo/event",
		"params":  json.RawMessage(params),
	})
	if err != nil {
		return nil
	}
	return withSeq(journalEntry{seq: e.seq, frame: frame})
}

// resolvedFrame builds the _devingo/permission_resolved notification.
// sessionId is omitted for unscoped requests (they have none).
func resolvedFrame(sid string, reqID int64, outcome string) json.RawMessage {
	params := map[string]any{
		"requestId": reqID,
		"outcome":   outcome,
	}
	if sid != "" {
		params["sessionId"] = sid
	}
	b, _ := json.Marshal(map[string]any{
		"jsonrpc": "2.0",
		"method":  "_devingo/permission_resolved",
		"params":  params,
	})
	return b
}

// markerFrame builds a generic gateway notification (journaled + broadcast).
func markerFrame(method string, params map[string]any) json.RawMessage {
	b, _ := json.Marshal(map[string]any{
		"jsonrpc": "2.0",
		"method":  method,
		"params":  params,
	})
	return b
}

func resultFrame(id json.RawMessage, result json.RawMessage) []byte {
	if len(result) == 0 {
		result = json.RawMessage(`{}`)
	}
	b, _ := json.Marshal(map[string]any{
		"jsonrpc": "2.0", "id": json.RawMessage(id), "result": result,
	})
	return b
}

func resultFrameRaw(id json.RawMessage, result []byte) []byte {
	return resultFrame(id, json.RawMessage(result))
}

func errorFrame(id json.RawMessage, code int, msg string) []byte {
	b, _ := json.Marshal(map[string]any{
		"jsonrpc": "2.0",
		"id":      json.RawMessage(id),
		"error":   map[string]any{"code": code, "message": msg},
	})
	return b
}

func rewriteID(frame []byte, newID json.RawMessage) []byte {
	var m map[string]json.RawMessage
	if json.Unmarshal(frame, &m) != nil {
		return frame
	}
	m["id"] = newID
	b, err := json.Marshal(m)
	if err != nil {
		return frame
	}
	return b
}

func rawInt(n int64) json.RawMessage {
	return json.RawMessage(strconv.FormatInt(n, 10))
}

// parseInternalID decodes the numeric ids the gateway issues. Agent-side
// ids (strings, other numbers) are rejected — only our counter is routable.
func parseInternalID(id json.RawMessage) (int64, bool) {
	s := strings.TrimSpace(string(id))
	if s == "" || s == "null" {
		return 0, false
	}
	n, err := strconv.ParseInt(s, 10, 64)
	return n, err == nil
}

func mustJSON(v any) []byte {
	b, _ := json.Marshal(v)
	return b
}

func truncStr(s string, n int) string {
	if len(s) > n {
		return s[:n] + "…"
	}
	return s
}

// ---------------------------------------------------------------- fs -----

// Sentinel errors for /fs/write — the handler maps them to status codes.
// errWriteFailed sanitizes filesystem errors: raw err strings carry
// absolute server paths and must not reach the client.
var (
	errSessionNotHosted = errors.New("session has no hosted workspace")
	errPathOutside      = errors.New("path escapes the session workspace")
	errWriteFailed      = errors.New("write failed")
)

// writeSessionFile writes content at path inside the session's recorded
// workspace. path must be relative and contained: a literal-clean check
// kills "."-padded traversals, and the deepest existing ancestor is then
// symlink-resolved so a mid-path symlink can't smuggle the target outside.
// Both sides go through filepath.Rel — a prefix-shared sibling (/work2 vs
// /work) never passes.
func (h *Hub) writeSessionFile(sid, path, content string) error {
	h.mu.Lock()
	var cwd string
	if s := h.sessions[sid]; s != nil {
		cwd = s.cwd
	}
	h.mu.Unlock()
	if cwd == "" {
		return errSessionNotHosted
	}
	root, err := filepath.Abs(cwd)
	if err != nil {
		return errSessionNotHosted
	}
	root, err = filepath.EvalSymlinks(root)
	if err != nil {
		return errSessionNotHosted // workspace no longer exists
	}
	clean := filepath.Clean(path)
	if filepath.IsAbs(clean) || filepath.VolumeName(clean) != "" ||
		clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return errPathOutside
	}
	target := filepath.Join(root, clean)
	if rel, err := filepath.Rel(root, target); err != nil ||
		rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return errPathOutside
	}
	// Resolve the deepest existing ancestor: a symlink anywhere along the
	// remaining path would already have made the literal Rel check lie, and
	// MkdirAll itself must not create directories outside the workspace.
	ancestor := filepath.Dir(target)
	for {
		if _, err := os.Lstat(ancestor); err == nil {
			break
		}
		parent := filepath.Dir(ancestor)
		if parent == ancestor { // reached the volume root without an existing parent
			return errPathOutside
		}
		ancestor = parent
	}
	resolved, err := filepath.EvalSymlinks(ancestor)
	if err != nil {
		return errPathOutside
	}
	if rel, err := filepath.Rel(root, resolved); err != nil ||
		rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return errPathOutside
	}
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return errWriteFailed
	}
	// Write via a temp file inside root + rename: os.WriteFile would
	// follow a final-component symlink or share an inode through a
	// hardlink — both escape the containment checks above. Rename
	// replaces the directory entry outright; readers also get the file
	// atomically instead of a mid-write truncation.
	tmp, err := os.CreateTemp(root, ".devingo-write-*")
	if err != nil {
		return errWriteFailed
	}
	defer os.Remove(tmp.Name())
	if _, err := tmp.WriteString(content); err != nil {
		tmp.Close()
		return errWriteFailed
	}
	if err := tmp.Chmod(0o644); err != nil {
		tmp.Close()
		return errWriteFailed
	}
	if err := tmp.Close(); err != nil {
		return errWriteFailed
	}
	if err := os.Rename(tmp.Name(), target); err != nil {
		return errWriteFailed
	}
	return nil
}

// ---------------------------------------------------------------- rpc ----

// rpcHead pulls the envelope fields the hub needs: id, method,
// params.sessionId, plus the raw result/error of responses.
type rpcHead struct {
	ID     json.RawMessage `json:"id"`
	Method string          `json:"method"`
	Params struct {
		SessionID string `json:"sessionId"`
		Cwd       string `json:"cwd"` // session/new|load workspace root
	} `json:"params"`
	Result json.RawMessage `json:"result"`
	Error  json.RawMessage `json:"error"`
}

// rpcParse extracts the routing envelope; ok is false for malformed JSON
// (Unmarshal can fill a partial head on bad input — those frames must not
// be forwarded or routed).
func rpcParse(line []byte) (rpcHead, bool) {
	var h rpcHead
	if err := json.Unmarshal(line, &h); err != nil {
		return rpcHead{}, false
	}
	return h, true
}
