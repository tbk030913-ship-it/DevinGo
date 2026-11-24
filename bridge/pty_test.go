package main

import (
	"bytes"
	"encoding/binary"
	"io"
	"testing"

	"github.com/hinshun/vt10x"
)

// decodeFrame mirrors apps/mobile TerminalFrame.kt — the Kotlin decoder the
// Android canvas runs — so a wire-format slip fails a test, not a phone.
type testCell struct {
	fg, bg uint32
	flags  uint16
	text   string
}
type testFrame struct {
	cols, rows                 uint16
	cursorX, cursorY           uint16
	cursorVisible, style, blink uint8
	defFG, defBG, cursor        uint32
	cells                       []testCell
}

func decodeFrame(t *testing.T, b []byte) testFrame {
	t.Helper()
	if len(b) < 30 {
		t.Fatalf("frame too short: %d bytes", len(b))
	}
	r := bytes.NewReader(b)
	u16 := func() uint16 {
		var v uint16
		if err := binary.Read(r, binary.LittleEndian, &v); err != nil {
			t.Fatal(err)
		}
		return v
	}
	u32 := func() uint32 {
		var v uint32
		if err := binary.Read(r, binary.LittleEndian, &v); err != nil {
			t.Fatal(err)
		}
		return v
	}
	u8 := func() uint8 {
		v, err := r.ReadByte()
		if err != nil {
			t.Fatal(err)
		}
		return v
	}
	if magic := u32(); magic != 0x54563354 {
		t.Fatalf("magic = %#x", magic)
	}
	if v := u16(); v != 1 {
		t.Fatalf("version = %d", v)
	}
	f := testFrame{
		cols: u16(), rows: u16(),
		cursorX: u16(), cursorY: u16(),
		cursorVisible: u8(), style: u8(), blink: u8(),
	}
	u8() // pad
	f.defFG, f.defBG, f.cursor = u32(), u32(), u32()
	for i := 0; i < int(f.cols)*int(f.rows); i++ {
		fg, bg, flags, n := u32(), u32(), u16(), u16()
		text := make([]byte, n)
		// io.ReadFull, not r.Read: an empty text at the exact buffer end
		// would otherwise report io.EOF.
		if _, err := io.ReadFull(r, text); err != nil {
			t.Fatal(err)
		}
		f.cells = append(f.cells, testCell{fg, bg, flags, string(text)})
	}
	if r.Len() != 0 {
		t.Fatalf("%d trailing bytes after %d cells", r.Len(), len(f.cells))
	}
	return f
}

// feed writes s into a fresh vt10x screen sized cols x rows.
func feed(cols, rows int, s string) vt10x.Terminal {
	vt := vt10x.New(vt10x.WithSize(cols, rows))
	vt.Write([]byte(s))
	return vt
}

func TestEncodeFrameDimensionsAndText(t *testing.T) {
	vt := feed(4, 2, "HI")
	f := decodeFrame(t, encodeFrame(vt))
	if f.cols != 4 || f.rows != 2 || len(f.cells) != 8 {
		t.Fatalf("dims %dx%d, %d cells", f.cols, f.rows, len(f.cells))
	}
	if f.cells[0].text != "H" || f.cells[1].text != "I" || f.cells[2].text != "" {
		t.Fatalf("cells %q %q %q", f.cells[0].text, f.cells[1].text, f.cells[2].text)
	}
	// Cursor sits just past the written text.
	if f.cursorX != 2 || f.cursorY != 0 || f.cursorVisible != 1 {
		t.Fatalf("cursor (%d,%d) visible=%d", f.cursorX, f.cursorY, f.cursorVisible)
	}
}

func TestEncodeFrameBoldRed(t *testing.T) {
	vt := feed(4, 2, "\x1b[1;31mHI")
	f := decodeFrame(t, encodeFrame(vt))
	c := f.cells[0]
	if c.text != "H" {
		t.Fatalf("cell text %q", c.text)
	}
	if c.flags&cellBold == 0 {
		t.Fatalf("bold flag missing: %#x", c.flags)
	}
	// SGR 1 + fg 1 (red): vt10x brightens bold ANSI to index 9 = 0xFF0000.
	if c.fg != 0xFFFF0000 {
		t.Fatalf("fg = %#x, want bright red", c.fg)
	}
	if c.bg != ptyDefaultBG {
		t.Fatalf("bg = %#x, want default", c.bg)
	}
}

func TestEncodeFrameTrueColor(t *testing.T) {
	vt := feed(2, 1, "\x1b[38;2;16;32;48mX")
	f := decodeFrame(t, encodeFrame(vt))
	if f.cells[0].fg != 0xFF102030 {
		t.Fatalf("truecolor fg = %#x", f.cells[0].fg)
	}
}

func TestEncodeFrameResize(t *testing.T) {
	vt := feed(4, 2, "HI")
	vt.Resize(10, 3)
	f := decodeFrame(t, encodeFrame(vt))
	if f.cols != 10 || f.rows != 3 || len(f.cells) != 30 {
		t.Fatalf("dims %dx%d, %d cells", f.cols, f.rows, len(f.cells))
	}
}

func TestEncodeFrameNonASCII(t *testing.T) {
	// vt10x stores one rune per cell — it has no wcwidth model, so CJK
	// glyphs occupy a single cell and the canvas just draws them wide.
	vt := feed(4, 1, "表x")
	f := decodeFrame(t, encodeFrame(vt))
	if f.cells[0].text != "表" || f.cells[1].text != "x" {
		t.Fatalf("cells %q %q", f.cells[0].text, f.cells[1].text)
	}
}

func TestRingCapsAtHalfMiB(t *testing.T) {
	var r ptyRing
	chunk := bytes.Repeat([]byte("x"), 100<<10) // 100 KiB chunks
	for i := 0; i < 8; i++ {                    // 800 KiB total
		r.write(chunk)
	}
	if r.bytes > ptyCopyAllCap {
		t.Fatalf("ring holds %d bytes, cap %d", r.bytes, ptyCopyAllCap)
	}
	if got := len(r.data()); got != r.bytes || got == 0 {
		t.Fatalf("data() = %d, bytes %d", got, r.bytes)
	}
	// One huge write keeps only its tail — the cap is a strict invariant.
	var r2 ptyRing
	r2.write(bytes.Repeat([]byte("y"), ptyCopyAllCap+1000))
	if r2.bytes != ptyCopyAllCap || len(r2.data()) != ptyCopyAllCap {
		t.Fatalf("single oversized write left %d bytes", r2.bytes)
	}
}
