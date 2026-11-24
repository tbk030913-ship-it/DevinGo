//go:build windows

// ConPTY-backed shell spawn (N6.4): one pseudo-console per terminal session,
// sized to the client's grid and rooted in the session's workspace cwd.
package main

import (
	"os"
	"os/exec"

	"github.com/UserExistsError/conpty"
)

func ptySupported() bool { return conpty.IsConPtyAvailable() }

// spawnPty starts the user's shell on a fresh pseudo-console.
func spawnPty(cwd string, cols, rows int) (ptyHandle, error) {
	return conpty.Start(shellCommand(),
		conpty.ConPtyDimensions(cols, rows),
		conpty.ConPtyWorkDir(cwd),
	)
}

// shellCommand prefers PowerShell, then %COMSPEC%, then cmd.exe.
func shellCommand() string {
	if p, err := exec.LookPath("powershell.exe"); err == nil {
		return p
	}
	if c := os.Getenv("COMSPEC"); c != "" {
		return c
	}
	return "cmd.exe"
}
