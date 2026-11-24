// Terminal console over WebSocket (N6.4): one ConPTY shell per hub session,
// rendered server-side by a vt10x screen and streamed to clients as t3's
// binary TerminalFrame — the same layout t3code's GhosttyBridge JNI encoder
// (t3_terminal_jni.cpp, MIT) writes and TerminalCanvasView decodes:
//
//	u32 magic "T3VT" (0x54563354 LE), u16 version (1),
//	u16 cols, rows, cursorX, cursorY,
//	u8 cursorVisible, cursorStyle (0 bar / 1 block / 2 underline / 3 hollow),
//	u8 cursorBlinking, u8 pad,
//	i32 default fg / bg / cursor ARGB,
//	then cols*rows cells: i32 fg, i32 bg, u16 flags, u16 utf8Len, utf8 text.
//
// Cell flags (TerminalCanvasView constants): bold=1, italic=2, invisible=16,
// strikethrough=32, overline=64, underline=128, selected=256.
//
// Client->bridge text frames are JSON: {"type":"attach",...} first, then
// {"type":"input","data":b64}, {"type":"resize","cols":c,"rows":r} and
// {"type":"copyall"}. Bridge->client: binary frames plus JSON events
// {"type":"exit","code":N}, {"type":"copyall","data":b64},
// {"type":"error","message":...}.
package main

import (
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/hinshun/vt10x"
)

const (
	// ptyIdleTTL kills a shell with zero attached sockets — the transcript
	// is only screen-deep (vt10x), so a forgotten shell is pure cost.
	ptyIdleTTL = 15 * time.Minute
	// ptyFlushDelay coalesces a PTY read burst into one frame broadcast.
	ptyFlushDelay = 16 * time.Millisecond
	// ptyCopyAllCap bounds the raw-output ring the copyall request serves.
	ptyCopyAllCap = 512 << 10
	// ptySendBuffer bounds a conn's outbound queue (one frame is cols*rows
	// cells — at 400x200 that's ~1MiB, so a few frames fill it).
	ptySendBuffer = 16
	// ptyAttachTimeout bounds the wait for the first (attach) message.
	ptyAttachTimeout = 10 * time.Second
	// ptyReadLimit bounds one inbound WS message (base64 pastes).
	ptyReadLimit = 1 << 20
)

// Default frame colors — t3's TerminalView theme defaults. uint32 because
// the ARGB bytes are what the wire carries; the Kotlin side reads a signed
// int of the same four bytes.
const (
	ptyDefaultFG     = uint32(0xFFD1D5DA)
	ptyDefaultBG     = uint32(0xFF24292E)
	ptyDefaultCursor = uint32(0xFF009FFF)
)

// ---------------------------------------------------------------- frames ---

// xtermPalette is the 256-entry xterm palette as ARGB: 16 system colors,
// the 6x6x6 cube and 24 grays. vt10x stores xterm-256 colors as their raw
// index and truecolor as r<<16|g<<8|b (see color.go).
var xtermPalette = func() [256]uint32 {
	var p [256]uint32
	base := [16]uint32{
		0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
		0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
	}
	for i, c := range base {
		p[i] = 0xFF000000 | c
	}
	level := [6]uint32{0, 95, 135, 175, 215, 255}
	for i := 16; i < 232; i++ {
		n := i - 16
		p[i] = 0xFF000000 | level[n/36]<<16 | level[(n/6)%6]<<8 | level[n%6]
	}
	for i := 232; i < 256; i++ {
		g := uint32(8 + 10*(i-232))
		p[i] = 0xFF000000 | g<<16 | g<<8 | g
	}
	return p
}()

// cell flag bits — the TerminalCanvasView constants.
const (
	cellBold      = 1
	cellItalic    = 2
	cellInvisible = 16
	cellStrike    = 32
	cellOverline  = 64
	cellUnderline = 128
)

// ptyColor resolves a vt10x Color to ARGB. ANSI/xterm-256 indices go through
// the palette; truecolor arrives packed r<<16|g<<8|b; the Default* markers
// (1<<24+) map onto the frame defaults.
func ptyColor(c vt10x.Color) uint32 {
	switch c {
	case vt10x.DefaultFG:
		return ptyDefaultFG
	case vt10x.DefaultBG:
		return ptyDefaultBG
	case vt10x.DefaultCursor:
		return ptyDefaultCursor
	}
	if c < 256 {
		return xtermPalette[c]
	}
	if c < 1<<24 {
		return 0xFF000000 | uint32(c)
	}
	return ptyDefaultFG
}

// vt10x glyph mode bits worth reporting: bold/italic/underline survive;
// vt10x already applied reverse (swapped colors) and the gfx charset remap
// when the cell was written, and it models no invisible/strike/overline.
func ptyCellFlags(mode int16) uint16 {
	var f uint16
	if mode&4 != 0 { // attrBold
		f |= cellBold
	}
	if mode&16 != 0 { // attrItalic
		f |= cellItalic
	}
	if mode&2 != 0 { // attrUnderline
		f |= cellUnderline
	}
	return f
}

// encodeFrame renders the vt10x screen as a t3 TerminalFrame payload —
// byte-for-byte the GhosttyBridge snapshot layout TerminalFrame.kt decodes.
func encodeFrame(vt vt10x.Terminal) []byte {
	vt.Lock()
	defer vt.Unlock()
	cols, rows := vt.Size()
	cursor := vt.Cursor()
	visible := vt.CursorVisible()

	buf := make([]byte, 0, 32+cols*rows*14)
	u16 := func(v uint16) { buf = binary.LittleEndian.AppendUint16(buf, v) }
	u32 := func(v uint32) { buf = binary.LittleEndian.AppendUint32(buf, v) }
	u32(0x54563354) // "T3VT"
	u16(1)
	u16(uint16(cols))
	u16(uint16(rows))
	u16(uint16(cursor.X))
	u16(uint16(cursor.Y))
	if visible {
		buf = append(buf, 1)
	} else {
		buf = append(buf, 0)
	}
	buf = append(buf, 1) // cursorStyle: block (vt10x doesn't track DECSCUSR)
	buf = append(buf, 0) // cursorBlinking
	buf = append(buf, 0) // pad
	u32(ptyDefaultFG)
	u32(ptyDefaultBG)
	u32(ptyDefaultCursor)
	for y := 0; y < rows; y++ {
		for x := 0; x < cols; x++ {
			cell := vt.Cell(x, y)
			var text []byte
			if cell.Char != 0 && cell.Char != ' ' {
				text = []byte(string(cell.Char))
			}
			u32(ptyColor(cell.FG))
			u32(ptyColor(cell.BG))
			u16(ptyCellFlags(cell.Mode))
			u16(uint16(len(text)))
			buf = append(buf, text...)
		}
	}
	return buf
}

// -------------------------------------------------------------- ring buf ---

// ptyRing is the copyall backlog: raw PTY output chunks capped at
// ptyCopyAllCap bytes, oldest evicted first.
type ptyRing struct {
	chunks [][]byte
	bytes  int
}

func (r *ptyRing) write(p []byte) {
	if len(p) == 0 {
		return
	}
	if len(p) >= ptyCopyAllCap {
		r.chunks = [][]byte{append([]byte(nil), p[len(p)-ptyCopyAllCap:]...)}
		r.bytes = ptyCopyAllCap
		return
	}
	cp := append([]byte(nil), p...)
	r.chunks = append(r.chunks, cp)
	r.bytes += len(cp)
	for r.bytes > ptyCopyAllCap {
		r.bytes -= len(r.chunks[0])
		r.chunks[0] = nil
		r.chunks = r.chunks[1:]
	}
}

func (r *ptyRing) data() []byte {
	out := make([]byte, 0, r.bytes)
	for _, c := range r.chunks {
		out = append(out, c...)
	}
	return out
}

// ptyHandle abstracts the spawned shell so the session logic is
// platform-agnostic; *conpty.ConPty implements it on Windows.
type ptyHandle interface {
	Read(p []byte) (int, error)
	Write(p []byte) (int, error)
	Close() error
	Resize(width, height int) error
	Wait(ctx context.Context) (uint32, error)
}

// ------------------------------------------------------------ connections ---

// wsOut is one queued outbound frame for a pty socket.
type wsOut struct {
	data   []byte
	binary bool
}

// ptyConn wraps one attached WebSocket: an ordered send queue plus the
// session it's attached to (for detach bookkeeping).
type ptyConn struct {
	conn  *websocket.Conn
	ctx   context.Context
	send  chan wsOut
	once  sync.Once
	sess  *ptySession
}

func (c *ptyConn) enqueue(data []byte, binary bool) {
	select {
	case c.send <- wsOut{data: data, binary: binary}:
	default:
		c.kick(websocket.StatusInternalError, "send queue overflow")
	}
}

func (c *ptyConn) enqueueJSON(v map[string]any) {
	b, err := json.Marshal(v)
	if err == nil {
		c.enqueue(b, false)
	}
}

// kick closes the socket exactly once; the read loop's failure then detaches.
func (c *ptyConn) kick(code websocket.StatusCode, reason string) {
	c.once.Do(func() { c.conn.Close(code, reason) })
}

// errorAndClose delivers the error JSON synchronously — a queued frame could
// be lost when the socket closes a moment later.
func (c *ptyConn) errorAndClose(message string, code websocket.StatusCode) {
	b, err := json.Marshal(map[string]any{"type": "error", "message": message})
	if err == nil {
		c.conn.Write(c.ctx, websocket.MessageText, b)
	}
	c.kick(code, message)
}

// writeLoop drains the send queue until the request context ends (the
// handler returning cancels it). send is never closed — a frame queued for
// a dead conn is just dropped, which beats a send-on-closed-channel panic
// when the session's exit broadcast races a departing conn's teardown.
func (c *ptyConn) writeLoop() {
	for {
		select {
		case m := <-c.send:
			typ := websocket.MessageText
			if m.binary {
				typ = websocket.MessageBinary
			}
			if err := c.conn.Write(c.ctx, typ, m.data); err != nil {
				c.kick(websocket.StatusInternalError, "write failed")
				return
			}
		case <-c.ctx.Done():
			return
		}
	}
}

// ------------------------------------------------------------- sessions ----

// ptySession is one shell + screen shared by every socket attached to a hub
// session. conns is the attach refcount; empty re-arms the idle kill timer.
type ptySession struct {
	id   string
	pty  ptyHandle
	vt   vt10x.Terminal

	mu             sync.Mutex
	conns          map[*ptyConn]bool
	vtPending      []byte // trailing incomplete UTF-8 vt10x didn't consume
	ring           ptyRing
	lastFrame      []byte
	flushScheduled bool
	killTimer      *time.Timer
	exited         bool
}

func newPtySession(id string, p ptyHandle, cols, rows int) *ptySession {
	return &ptySession{
		id:    id,
		pty:   p,
		vt:    vt10x.New(vt10x.WithSize(cols, rows)),
		conns: map[*ptyConn]bool{},
	}
}

// pump feeds PTY output into the screen + copyall ring and schedules a
// coalesced frame broadcast per read burst.
func (s *ptySession) pump() {
	buf := make([]byte, 32<<10)
	for {
		n, err := s.pty.Read(buf)
		if n > 0 {
			s.mu.Lock()
			s.ring.write(buf[:n])
			s.vtPending = append(s.vtPending, buf[:n]...)
			consumed, _ := s.vt.Write(s.vtPending)
			s.vtPending = s.vtPending[consumed:]
			if !s.flushScheduled {
				s.flushScheduled = true
				go s.flush()
			}
			s.mu.Unlock()
		}
		if err != nil {
			return // waitForExit reports the exit + closes conns
		}
	}
}

// broadcastNow encodes the current screen and sends it to every attached
// conn — used after resize (immediate frame) and by the coalesced flush.
func (s *ptySession) broadcastNow() {
	frame := encodeFrame(s.vt)
	s.mu.Lock()
	s.lastFrame = frame
	conns := make([]*ptyConn, 0, len(s.conns))
	for c := range s.conns {
		conns = append(conns, c)
	}
	s.mu.Unlock()
	for _, c := range conns {
		c.enqueue(frame, true)
	}
}

// flush is the delayed broadcast a read burst schedules: it wakes ~16ms
// later, clears the scheduled flag under mu (a burst arriving after the
// reset schedules its own flush — no lost tail), then broadcasts.
func (s *ptySession) flush() {
	time.Sleep(ptyFlushDelay)
	s.mu.Lock()
	s.flushScheduled = false
	s.mu.Unlock()
	s.broadcastNow()
}

// waitForExit broadcasts the shell's exit then tears the session down.
func (s *ptySession) waitForExit(reg *ptyRegistry) {
	code, _ := s.pty.Wait(context.Background())
	reg.remove(s.id, s)
	s.mu.Lock()
	s.exited = true
	conns := make([]*ptyConn, 0, len(s.conns))
	for c := range s.conns {
		conns = append(conns, c)
	}
	s.mu.Unlock()
	exitJSON, _ := json.Marshal(map[string]any{"type": "exit", "code": int(code)})
	for _, c := range conns {
		// Synchronous write — a queued frame can be lost to the close.
		c.conn.Write(c.ctx, websocket.MessageText, exitJSON)
		c.kick(websocket.StatusNormalClosure, "pty exited")
	}
	s.pty.Close()
}

// attach registers a conn and replays the current frame. Refcount bookkeeping
// cancels the idle kill timer. A conn racing in just as the shell exited is
// closed instead — the registry already dropped the session, so its retry
// spawns a fresh shell.
func (s *ptySession) attach(c *ptyConn) {
	s.mu.Lock()
	if s.exited {
		s.mu.Unlock()
		// Synchronous write — a queued frame can be lost to the close.
		b, _ := json.Marshal(map[string]any{"type": "exit", "code": 0})
		c.conn.Write(c.ctx, websocket.MessageText, b)
		c.kick(websocket.StatusNormalClosure, "pty exited")
		return
	}
	if s.killTimer != nil {
		s.killTimer.Stop()
		s.killTimer = nil
	}
	s.conns[c] = true
	c.sess = s
	frame := s.lastFrame
	s.mu.Unlock()
	if frame == nil {
		frame = encodeFrame(s.vt)
	}
	c.enqueue(frame, true)
}

// detach drops a conn; at zero attachments the idle kill timer arms.
func (s *ptySession) detach(c *ptyConn) {
	s.mu.Lock()
	delete(s.conns, c)
	idle := len(s.conns) == 0 && !s.exited
	if idle && s.killTimer == nil {
		s.killTimer = time.AfterFunc(ptyIdleTTL, func() {
			s.pty.Close() // waitForExit does the rest
		})
	}
	s.mu.Unlock()
}

// ------------------------------------------------------------ registry -----

// ptyRegistry keeps one live ptySession per key (hub sessionId; "" is the
// default-cwd shell — never a hub session, so it can't collide).
type ptyRegistry struct {
	mu   sync.Mutex
	byID map[string]*ptySession
}

var ptySessions = newPtyRegistry()

func newPtyRegistry() *ptyRegistry {
	return &ptyRegistry{byID: map[string]*ptySession{}}
}

// attach returns the live session for key, spawning one at (cwd, cols x rows)
// when none exists.
func (r *ptyRegistry) attach(key, cwd string, cols, rows int, c *ptyConn) (*ptySession, error) {
	r.mu.Lock()
	s := r.byID[key]
	r.mu.Unlock()
	if s != nil {
		s.attach(c)
		return s, nil
	}
	p, err := spawnPty(cwd, cols, rows)
	if err != nil {
		return nil, err
	}
	s = newPtySession(key, p, cols, rows)
	r.mu.Lock()
	// A concurrent attach for the same key spawned too — keep the first.
	if prev := r.byID[key]; prev != nil {
		r.mu.Unlock()
		p.Close()
		prev.attach(c)
		return prev, nil
	}
	r.byID[key] = s
	r.mu.Unlock()
	go s.pump()
	go s.waitForExit(r)
	s.attach(c)
	log.Printf("pty: spawned shell for %q (cwd %s, %dx%d)", key, cwd, cols, rows)
	return s, nil
}

// detach unregisters the conn from its session.
func (r *ptyRegistry) detach(c *ptyConn) {
	if s := c.sess; s != nil {
		s.detach(c)
	}
}

// remove drops a session from the map — identity-checked so an evicted
// session never deletes its respawned successor.
func (r *ptyRegistry) remove(key string, s *ptySession) {
	r.mu.Lock()
	if r.byID[key] == s {
		delete(r.byID, key)
	}
	r.mu.Unlock()
}

// dropSession kills the shell bound to a hub session that went away
// (evicted, deleted, or expired) — called from Hub.dropSessionLocked.
func (r *ptyRegistry) dropSession(sid string) {
	r.mu.Lock()
	s := r.byID[sid]
	delete(r.byID, sid)
	r.mu.Unlock()
	if s != nil {
		s.pty.Close()
	}
}

// ----------------------------------------------------------------- wire ----

type ptyClientMsg struct {
	Type      string `json:"type"`
	SessionID string `json:"sessionId"`
	Cols      int    `json:"cols"`
	Rows      int    `json:"rows"`
	Data      string `json:"data"`
}

// ptyHandler upgrades into a terminal-console socket (N6.4). Same auth +
// origin gates as /acp. The first message attaches a session's workspace
// shell; later messages feed input, resize or ask for the copyall ring.
func ptyHandler(w http.ResponseWriter, r *http.Request) {
	if !ptySupported() {
		http.Error(w, "terminal console unsupported on this platform", http.StatusNotImplemented)
		return
	}
	if !authorized(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	if !validOrigin(r) {
		http.Error(w, "forbidden origin", http.StatusForbidden)
		return
	}
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		InsecureSkipVerify: true,
		CompressionMode:    websocket.CompressionNoContextTakeover,
	})
	if err != nil {
		log.Printf("pty ws accept: %v", err)
		return
	}
	conn.SetReadLimit(ptyReadLimit)

	pc := &ptyConn{conn: conn, ctx: r.Context(), send: make(chan wsOut, ptySendBuffer)}
	go pc.writeLoop()

	readMsg := func(ctx context.Context) (*ptyClientMsg, error) {
		typ, data, err := conn.Read(ctx)
		if err != nil {
			return nil, err
		}
		if typ != websocket.MessageText {
			return nil, errors.New("expected a text message")
		}
		var m ptyClientMsg
		if err := json.Unmarshal(data, &m); err != nil {
			return nil, err
		}
		return &m, nil
	}

	// The first message must be attach: it names the session (resolved to
	// its recorded workspace cwd) and the initial grid size.
	actx, cancel := context.WithTimeout(r.Context(), ptyAttachTimeout)
	attach, err := readMsg(actx)
	cancel()
	if err != nil {
		pc.kick(websocket.StatusProtocolError, "attach timeout")
		return
	}
	if attach.Type != "attach" {
		pc.kick(websocket.StatusProtocolError, "first message must be attach")
		return
	}
	cwd := *defaultDir
	key := ""
	if attach.SessionID != "" {
		resolved, err := bridgeHub.sessionCwd(attach.SessionID)
		if err != nil {
			pc.errorAndClose(err.Error(), websocket.StatusPolicyViolation)
			return
		}
		cwd, key = resolved, attach.SessionID
	}
	cols := clampInt(attach.Cols, 2, 400, 80)
	rows := clampInt(attach.Rows, 2, 200, 24)
	if _, err := ptySessions.attach(key, cwd, cols, rows, pc); err != nil {
		pc.errorAndClose("spawn shell: "+err.Error(), websocket.StatusInternalError)
		return
	}
	defer ptySessions.detach(pc)

	for {
		m, err := readMsg(r.Context())
		if err != nil {
			return
		}
		s := pc.sess
		if s == nil {
			return
		}
		switch m.Type {
		case "input":
			if b, err := base64.StdEncoding.DecodeString(m.Data); err == nil && len(b) > 0 {
				s.pty.Write(b)
			}
		case "resize":
			c := clampInt(m.Cols, 2, 400, 0)
			rw := clampInt(m.Rows, 2, 200, 0)
			if c > 0 && rw > 0 {
				s.pty.Resize(c, rw)
				s.vt.Resize(c, rw)
				s.broadcastNow()
			}
		case "copyall":
			s.mu.Lock()
			data := s.ring.data()
			s.mu.Unlock()
			pc.enqueueJSON(map[string]any{
				"type": "copyall",
				"data": base64.StdEncoding.EncodeToString(data),
			})
		}
	}
}

func clampInt(v, lo, hi, def int) int {
	if v == 0 {
		return def
	}
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}
