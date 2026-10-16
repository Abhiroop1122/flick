//go:build !darwin && !windows

package main

// Linux: used only for development and the automated tests.

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
)

const osName = "linux"

func configDir() string {
	d, err := os.UserConfigDir()
	if err != nil {
		d = os.TempDir()
	}
	return filepath.Join(d, "Flick")
}

func defaultSaveDir() string {
	h, _ := os.UserHomeDir()
	return filepath.Join(h, "Downloads")
}

func computerName() string {
	n, _ := os.Hostname()
	return strings.TrimSuffix(n, ".local")
}

func freeBytes(dir string) (uint64, bool) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(dir, &st); err != nil {
		return 0, false
	}
	return st.Bavail * uint64(st.Bsize), true
}

func openURL(u string)      { _ = exec.Command("xdg-open", u).Start() }
func openPath(p string)     { _ = exec.Command("xdg-open", p).Start() }
func revealPath(p string)   { _ = exec.Command("xdg-open", filepath.Dir(p)).Start() }
func copyText(text string)  {}
func launchedAtLogin() bool { return false }

func newPlatformUI(cfg *Config) UI { return &headlessUI{enc: json.NewEncoder(os.Stderr)} }

func platformAgentStarted(s *Server) {}

func platformInstall(dir string) {
	exe, _ := os.Executable()
	cmd := exec.Command(exe, "--agent")
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	_ = cmd.Start()
}

func platformUninstall() {}

func autostartConfigured() bool { return false }
func ensureAgent(dir string)    {}

func platformAlert(title, msg string) { logf("%s: %s", title, msg) }
