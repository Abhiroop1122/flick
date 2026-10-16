package main

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"runtime/debug"
	"time"
)

const Version = "2.2.0"

var logger = log.New(os.Stderr, "", log.LstdFlags)

func logf(format string, a ...any) { logger.Printf(format, a...) }

func setupLogFile(dir string) {
	p := filepath.Join(dir, "flick.log")
	if st, err := os.Stat(p); err == nil && st.Size() > 1<<20 {
		_ = os.Rename(p, p+".old")
	}
	if f, err := os.OpenFile(p, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600); err == nil {
		logger.SetOutput(f)
	}
}

type agentInfo struct {
	Port    int    `json:"port"`
	Token   string `json:"token"`
	PID     int    `json:"pid"`
	Version string `json:"version"`
	FP      string `json:"fp"`
}

// runAgent is the always-on background receiver.
func runAgent() {
	// Keep memory small: collect garbage earlier and cap the heap softly.
	debug.SetGCPercent(50)
	debug.SetMemoryLimit(40 << 20)

	dir := configDir()
	_ = os.MkdirAll(dir, 0o700)
	setupLogFile(dir)
	release, ok := lockAgent(dir)
	if !ok {
		logf("another Flick agent is already running")
		return
	}
	defer release()

	cfg := loadConfig(dir)
	cert, fp, err := loadOrCreateCert(dir, cfg.Get().ID)
	if err != nil {
		logf("certificate: %v", err)
		os.Exit(1)
	}
	ui := newPlatformUI(cfg)
	srv := newServer(cfg, cert, fp, ui)
	for {
		if err := srv.Start(); err == nil {
			break
		} else {
			logf("listen: %v (retrying)", err)
		}
		time.Sleep(5 * time.Second)
	}
	info := agentInfo{Port: srv.Port, Token: srv.LocalToken, PID: os.Getpid(), Version: Version, FP: fp}
	b, _ := json.Marshal(info)
	_ = os.WriteFile(filepath.Join(dir, "agent.json"), b, 0o600)
	logf("Flick %s running as %q on port %d", Version, cfg.DisplayName(), srv.Port)

	go runDiscovery(srv)
	go announce(srv)
	platformAgentStarted(srv)
	ui.Run()
}

// ---------------------------------------------------------------- talking to a running agent

type agentStatus struct {
	Version string `json:"version"`
	Exe     string `json:"exe"`
	PID     int    `json:"pid"`
	Name    string `json:"name"`
}

func agentClient(dir string) (*http.Client, agentInfo, error) {
	var info agentInfo
	b, err := os.ReadFile(filepath.Join(dir, "agent.json"))
	if err != nil {
		return nil, info, err
	}
	if err := json.Unmarshal(b, &info); err != nil || info.Port == 0 {
		return nil, info, errors.New("bad agent.json")
	}
	tr := &http.Transport{TLSClientConfig: &tls.Config{
		InsecureSkipVerify: true, // we pin the agent's own certificate below
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			if len(raw) == 0 || fingerprint(raw[0]) != info.FP {
				return errors.New("unexpected certificate")
			}
			return nil
		},
	}}
	return &http.Client{Transport: tr, Timeout: 3 * time.Second}, info, nil
}

func agentCall(dir, method, path string) ([]byte, error) {
	return agentCallBody(dir, method, path, nil)
}

func agentCallBody(dir, method, path string, body []byte) ([]byte, error) {
	c, info, err := agentClient(dir)
	if err != nil {
		return nil, err
	}
	req, _ := http.NewRequest(method, fmt.Sprintf("https://127.0.0.1:%d%s", info.Port, path), bytes.NewReader(body))
	req.Header.Set("X-Flick-Local", info.Token)
	res, err := c.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	var buf bytes.Buffer
	_, _ = buf.ReadFrom(res.Body)
	if res.StatusCode != 200 {
		return nil, fmt.Errorf("HTTP %d", res.StatusCode)
	}
	return buf.Bytes(), nil
}

func pingAgent(dir string) (agentStatus, bool) {
	var st agentStatus
	b, err := agentCall(dir, "GET", "/v1/local/status")
	if err != nil || json.Unmarshal(b, &st) != nil {
		return st, false
	}
	return st, true
}

func waitForAgent(dir string, d time.Duration) bool {
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if _, ok := pingAgent(dir); ok {
			return true
		}
		time.Sleep(250 * time.Millisecond)
	}
	return false
}

func waitForAgentGone(dir string, d time.Duration) {
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if _, ok := pingAgent(dir); !ok {
			return
		}
		time.Sleep(200 * time.Millisecond)
	}
}

// stopAgent asks a running agent to exit (used before replacing it with a new version).
func stopAgent(dir string) {
	if _, err := agentCall(dir, "POST", "/v1/local/quit"); err == nil {
		waitForAgentGone(dir, 5*time.Second)
	}
}

// runSend is what "Send with Flick" / "Send to → Phone (Flick)" runs: it hands
// the files to the background agent, which asks which phone to send them to.
func runSend(paths []string) {
	dir := configDir()
	var abs []string
	for _, p := range paths {
		if a, err := filepath.Abs(p); err == nil {
			abs = append(abs, a)
		}
	}
	if len(abs) == 0 {
		return
	}
	if _, ok := pingAgent(dir); !ok {
		ensureAgent(dir)
		waitForAgent(dir, 8*time.Second)
	}
	body, _ := json.Marshal(map[string]any{"paths": abs})
	if _, err := agentCallBody(dir, "POST", "/v1/local/send", body); err != nil {
		platformAlert("Flick isn't running", "Open Flick once to turn it on, then try sending again.")
	}
}

// runLauncher handles a double-click on Flick: installs and starts the
// background agent the first time, and shows its status after that.
func runLauncher() {
	dir := configDir()
	_ = os.MkdirAll(dir, 0o700)
	if autostartConfigured() && launchedAtLogin() {
		ensureAgent(dir) // opened by an old login item: stay quiet
		return
	}
	if st, ok := pingAgent(dir); ok && st.Version == Version {
		if _, err := agentCall(dir, "POST", "/v1/local/show"); err == nil {
			return
		}
	}
	platformInstall(dir)
}
