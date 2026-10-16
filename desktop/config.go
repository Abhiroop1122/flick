package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Trusted is a phone that ticked "Always accept".
type Trusted struct {
	Name    string `json:"name"`
	Model   string `json:"model"`
	Key     string `json:"key"`
	AddedAt int64  `json:"addedAt"`
}

// Settings are stored as JSON in the user's config folder. The file name and
// fields match the earlier Electron version, so an existing computer keeps its
// identity, certificate and trusted phones.
type Settings struct {
	ID                string             `json:"id"`
	Name              string             `json:"name,omitempty"`
	SaveDir           string             `json:"saveDir,omitempty"`
	AutoAcceptTrusted bool               `json:"autoAcceptTrusted"`
	OpenLinks         bool               `json:"openLinks"`
	RevealWhenDone    bool               `json:"revealWhenDone"`
	PlaySound         bool               `json:"playSound"`
	ShareClipboard    bool               `json:"shareClipboard"`
	Trusted           map[string]Trusted `json:"trusted"`
	FirewallDone      bool               `json:"firewallDone,omitempty"`
	AgentVersion      string             `json:"agentVersion,omitempty"`
}

type Config struct {
	mu   sync.Mutex
	dir  string
	file string
	s    Settings
}

func loadConfig(dir string) *Config {
	_ = os.MkdirAll(dir, 0o700)
	c := &Config{dir: dir, file: filepath.Join(dir, "flick-settings.json")}
	c.s = Settings{AutoAcceptTrusted: true, OpenLinks: true, RevealWhenDone: true, PlaySound: true, ShareClipboard: true}
	if b, err := os.ReadFile(c.file); err == nil {
		_ = json.Unmarshal(b, &c.s)
		if c.s.AgentVersion == "" { // coming from the Electron version: show files on arrival, like AirDrop
			c.s.RevealWhenDone = true
		}
	}
	if c.s.ID == "" {
		c.s.ID = newUUID()
	}
	if c.s.Trusted == nil {
		c.s.Trusted = map[string]Trusted{}
	}
	if c.s.SaveDir == "" {
		c.s.SaveDir = defaultSaveDir()
	}
	c.s.AgentVersion = Version
	c.saveLocked()
	return c
}

func (c *Config) Get() Settings {
	c.mu.Lock()
	defer c.mu.Unlock()
	s := c.s
	s.Trusted = make(map[string]Trusted, len(c.s.Trusted))
	for k, v := range c.s.Trusted {
		s.Trusted[k] = v
	}
	return s
}

func (c *Config) Update(f func(*Settings)) {
	c.mu.Lock()
	defer c.mu.Unlock()
	f(&c.s)
	c.saveLocked()
}

func (c *Config) Reload() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if b, err := os.ReadFile(c.file); err == nil {
		s := Settings{ShareClipboard: true}
		if json.Unmarshal(b, &s) == nil && s.ID != "" {
			if s.Trusted == nil {
				s.Trusted = map[string]Trusted{}
			}
			c.s = s
		}
	}
}

func (c *Config) saveLocked() {
	b, err := json.MarshalIndent(c.s, "", "  ")
	if err != nil {
		return
	}
	tmp := c.file + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		logf("save settings: %v", err)
		return
	}
	if err := os.Rename(tmp, c.file); err != nil {
		logf("save settings: %v", err)
	}
}

// DisplayName is what phones see.
func (c *Config) DisplayName() string {
	c.mu.Lock()
	n := c.s.Name
	c.mu.Unlock()
	if n != "" {
		return n
	}
	return computerName()
}

func (c *Config) IsTrusted(senderID, key string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	t, ok := c.s.Trusted[senderID]
	if !ok || key == "" || len(key) != len(t.Key) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(key), []byte(t.Key)) == 1
}

func (c *Config) Trust(id, name, model string) string {
	key := randHex(32)
	c.Update(func(s *Settings) {
		s.Trusted[id] = Trusted{Name: name, Model: model, Key: key, AddedAt: time.Now().UnixMilli()}
	})
	return key
}

func (c *Config) ForgetTrusted() {
	c.Update(func(s *Settings) { s.Trusted = map[string]Trusted{} })
}

func randHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

func newUUID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
