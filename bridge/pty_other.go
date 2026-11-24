//go:build !windows

// The terminal console is ConPTY-backed and Windows-only (N6.4); the /pty
// handler answers 501 where spawnPty can't exist.
package main

import "errors"

func ptySupported() bool { return false }

func spawnPty(cwd string, cols, rows int) (ptyHandle, error) {
	return nil, errors.New("terminal console requires Windows ConPTY")
}
