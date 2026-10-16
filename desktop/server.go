package main

import (
	"context"
	"crypto/subtle"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	basePort       = 47475
	maxPort        = 47480
	sessionIdle    = 2 * time.Minute
	maxPendingAsks = 4
	maxItems       = 2000
	maxText        = 100_000
	maxThumbB64    = 280_000
	readStall      = 60 * time.Second
)

type Item struct {
	Name string
	Size int64 // -1 when unknown
	Mime string
}

type Sender struct {
	ID, Name, Model, Platform, TrustKey string
}

type Ask struct {
	Pair        bool // the phone asks to connect (send without asking, receive the clipboard)
	ID          string
	Sender      Sender
	Items       []Item
	Text        string
	Thumb       []byte // small JPEG from the phone, may be nil
	TotalSize   int64
	Description string
	From        string
	Trusted     bool
}

type Decision struct {
	Accept bool
	Trust  bool
}

// UI is implemented per platform (Mac dialogs, Windows dialogs + tray, headless for tests).
type UI interface {
	Attach(s *Server)
	Ask(ctx context.Context, a *Ask) Decision // blocks; must return promptly when ctx is cancelled
	Started(a *Ask, s *Session)               // files are about to arrive
	Finished(a *Ask, s *Session)              // all saved, or failed
	Text(a *Ask, text string)                 // a link or text was accepted
	Notice(title, body string)
	ShowControl() // the person opened Flick again
	Run()         // blocks for the life of the agent
	Quit()

	// Sending from this computer to a phone.
	PhonesChanged()                                   // a phone connected or disconnected
	PickPhone(phones []PhoneInfo, desc string) string // returns a phone ID, or "" if cancelled
	NoPhones(desc string)                             // nothing is connected
	OfferStarted(o *Offer)                            // waiting for the phone, then progress
	OfferFinished(o *Offer)
	ClipFromPhone(phoneName, text string) // put a phone's copied text on this computer's clipboard
}

// ---------------------------------------------------------------- sessions

type itemState int

const (
	itemPending itemState = iota
	itemReceiving
	itemDone
	itemFailed
)

type sessItem struct {
	Item
	state itemState
	abort func()
}

type Session struct {
	ID, Token string
	Ask       *Ask
	SaveDir   string

	mu         sync.Mutex
	items      []*sessItem
	saved      []string
	state      int // 0 active, 1 done, 2 failed
	reason     string
	started    time.Time
	finishedAt time.Time
	rateAt     time.Time
	rateBytes  int64
	rate       float64

	received     atomic.Int64
	lastActivity atomic.Int64
	done         chan struct{}
}

type Progress struct {
	Received, Total int64
	Rate            float64
	FilesDone       int
	Count           int
}

func (s *Session) Done() <-chan struct{} { return s.done }

func (s *Session) Progress() Progress {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	got := s.received.Load()
	if el := now.Sub(s.rateAt); el >= 700*time.Millisecond {
		inst := float64(got-s.rateBytes) / el.Seconds()
		if s.rate == 0 {
			s.rate = inst
		} else {
			s.rate = s.rate*0.5 + inst*0.5
		}
		s.rateAt, s.rateBytes = now, got
	}
	done := 0
	for _, it := range s.items {
		if it.state == itemDone {
			done++
		}
	}
	total := s.Ask.TotalSize
	if got > total {
		total = got
	}
	return Progress{Received: got, Total: total, Rate: s.rate, FilesDone: done, Count: len(s.items)}
}

// Result: ok, failure reason, files saved so far.
func (s *Session) Result() (bool, string, []string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state == 1, s.reason, append([]string(nil), s.saved...)
}

func (s *Session) failed() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state == 2
}

// ---------------------------------------------------------------- server

type Server struct {
	cfg        *Config
	cert       tls.Certificate
	FP         string
	ui         UI
	askTimeout time.Duration
	Port       int
	LocalToken string
	OnQuit     func()

	pending  atomic.Int32
	mu       sync.Mutex
	sessions map[string]*Session
	http     *http.Server

	linkMu    sync.Mutex
	links     map[string]*Link
	offerMu   sync.Mutex
	offers    map[string]*Offer
	clipMu    sync.Mutex
	lastClip  string
	sendMu    sync.Mutex
	sendQueue []string
	sendTimer *time.Timer
	testMode  bool
}

type Identity struct {
	Flick int    `json:"flick"`
	Type  string `json:"type,omitempty"`
	ID    string `json:"id"`
	Name  string `json:"name"`
	OS    string `json:"os"`
	Port  int    `json:"port"`
	FP    string `json:"fp"`
	V     string `json:"v"`
}

func newServer(cfg *Config, cert tls.Certificate, fp string, ui UI) *Server {
	s := &Server{cfg: cfg, cert: cert, FP: fp, ui: ui, askTimeout: 120 * time.Second,
		sessions: map[string]*Session{}, LocalToken: randHex(24),
		links: map[string]*Link{}, offers: map[string]*Offer{}}
	ui.Attach(s)
	return s
}

func (s *Server) Identity() Identity {
	return Identity{Flick: 1, ID: s.cfg.Get().ID, Name: s.cfg.DisplayName(), OS: osName, Port: s.Port, FP: s.FP, V: Version}
}

// Start listens on the first free port in 47475–47480.
func (s *Server) Start() error {
	var ln net.Listener
	var err error
	for p := basePort; p <= maxPort; p++ {
		ln, err = net.Listen("tcp4", fmt.Sprintf(":%d", p))
		if err == nil {
			s.Port = p
			break
		}
	}
	if ln == nil {
		return err
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/info", s.handleInfo)
	mux.HandleFunc("/v1/ask", s.handleAsk)
	mux.HandleFunc("/v1/upload", s.handleUpload)
	mux.HandleFunc("/v1/cancel", s.handleCancel)
	mux.HandleFunc("/v1/local/", s.handleLocal)
	mux.HandleFunc("/v1/link", s.handleLink)
	mux.HandleFunc("/v1/offer/file", s.handleOfferFile)
	mux.HandleFunc("/v1/offer/status", s.handleOfferStatus)
	mux.HandleFunc("/v1/clip", s.handleClip)
	if s.testMode {
		mux.HandleFunc("/v1/test/", s.handleTest)
	}
	s.http = &http.Server{
		Handler:           recoverer(mux),
		ReadHeaderTimeout: 30 * time.Second,
		IdleTimeout:       30 * time.Second,
		MaxHeaderBytes:    32 << 10,
		TLSNextProto:      map[string]func(*http.Server, *tls.Conn, http.Handler){}, // HTTP/1.1 only
		ErrorLog:          nil,
	}
	tlsLn := tls.NewListener(ln, &tls.Config{
		Certificates: []tls.Certificate{s.cert},
		MinVersion:   tls.VersionTLS12,
		NextProtos:   []string{"http/1.1"},
	})
	go func() {
		if err := s.http.Serve(tlsLn); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logf("server stopped: %v", err)
		}
	}()
	go s.sweep()
	go s.sweepOffers()
	return nil
}

func recoverer(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil && v != http.ErrAbortHandler {
				logf("panic: %v", v)
			}
		}()
		h.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	b, _ := json.Marshal(v)
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Length", strconv.Itoa(len(b)))
	w.WriteHeader(status)
	_, _ = w.Write(b)
}

func cleanStr(v string, max int) string {
	v = strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f {
			return ' '
		}
		return r
	}, strings.ToValidUTF8(v, ""))
	v = strings.TrimSpace(v)
	if r := []rune(v); len(r) > max {
		v = string(r[:max])
	}
	return v
}

func (s *Server) handleInfo(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, map[string]string{"error": "method"})
		return
	}
	writeJSON(w, 200, s.Identity())
}

// ---------------------------------------------------------------- ask

type askBody struct {
	Sender *struct {
		ID       string `json:"id"`
		Name     string `json:"name"`
		Model    string `json:"model"`
		Platform string `json:"platform"`
		TrustKey string `json:"trustKey"`
	} `json:"sender"`
	Items []struct {
		Name string          `json:"name"`
		Size json.RawMessage `json:"size"`
		Mime string          `json:"mime"`
	} `json:"items"`
	Text  *string `json:"text"`
	Thumb string  `json:"thumb"`
	Pair  bool    `json:"pair"`
}

func (s *Server) handleAsk(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]string{"error": "method"})
		return
	}
	raw, err := io.ReadAll(io.LimitReader(r.Body, 1<<20+1))
	if err != nil {
		return
	}
	if len(raw) > 1<<20 {
		writeJSON(w, 413, map[string]string{"error": "too large"})
		return
	}
	var body askBody
	if json.Unmarshal(raw, &body) != nil {
		writeJSON(w, 400, map[string]string{"error": "bad json"})
		return
	}
	if body.Sender == nil || strings.TrimSpace(body.Sender.ID) == "" {
		writeJSON(w, 400, map[string]string{"error": "sender required"})
		return
	}
	if len(body.Items) > maxItems {
		writeJSON(w, 400, map[string]string{"error": "too many items"})
		return
	}
	sender := Sender{
		ID:       cleanStr(body.Sender.ID, 80),
		Name:     cleanStr(body.Sender.Name, 60),
		Model:    cleanStr(body.Sender.Model, 80),
		Platform: cleanStr(body.Sender.Platform, 20),
		TrustKey: body.Sender.TrustKey,
	}
	if sender.Name == "" {
		sender.Name = "Android phone"
	}
	items := make([]Item, 0, len(body.Items))
	var total int64
	for _, it := range body.Items {
		size := int64(-1)
		if n, err := strconv.ParseFloat(strings.TrimSpace(string(it.Size)), 64); err == nil && n >= 0 && n < 1<<62 {
			size = int64(n)
		}
		mime := cleanStr(it.Mime, 100)
		if mime == "" {
			mime = "application/octet-stream"
		}
		items = append(items, Item{Name: safeName(it.Name), Size: size, Mime: mime})
		if size > 0 {
			total += size
		}
	}
	text := ""
	if body.Text != nil {
		text = *body.Text
		if len(text) > maxText {
			text = text[:maxText]
		}
		text = strings.ToValidUTF8(text, "")
	}
	if len(items) == 0 && strings.TrimSpace(text) == "" && !body.Pair {
		writeJSON(w, 400, map[string]string{"error": "nothing to send"})
		return
	}
	pair := body.Pair && len(items) == 0 && strings.TrimSpace(text) == ""
	var thumb []byte
	if body.Thumb != "" && len(body.Thumb) <= maxThumbB64 {
		thumb, _ = base64.StdEncoding.DecodeString(body.Thumb)
	}

	st := s.cfg.Get()
	if len(items) > 0 && total > 0 {
		if free, ok := freeBytes(st.SaveDir); ok && free < uint64(total)+50<<20 {
			s.ui.Notice("Not enough space", fmt.Sprintf("Can't receive %s from %s: the disk is almost full.", humanSize(total), sender.Name))
			writeJSON(w, 507, map[string]any{"accepted": false, "reason": "no-space"})
			return
		}
	}

	desc := describeItems(items)
	if len(items) == 0 {
		desc = "some text"
		if isHTTPURL(text) {
			desc = "a link"
		}
		if pair {
			desc = "a connection"
		}
	}
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	trusted := s.cfg.IsTrusted(sender.ID, sender.TrustKey)
	ask := &Ask{
		Pair: pair, ID: randHex(8), Sender: sender, Items: items, Text: text, Thumb: thumb,
		TotalSize: total, Description: desc, From: host, Trusted: trusted,
	}

	var dec Decision
	auto := false
	if trusted && st.AutoAcceptTrusted {
		dec = Decision{Accept: true}
		auto = true
		if t, ok := st.Trusted[sender.ID]; ok && t.Name != sender.Name {
			s.cfg.Update(func(x *Settings) { t.Name = sender.Name; x.Trusted[sender.ID] = t })
		}
	} else {
		if s.pending.Add(1) > maxPendingAsks {
			s.pending.Add(-1)
			writeJSON(w, 429, map[string]any{"accepted": false, "reason": "busy"})
			return
		}
		ctx, cancel := context.WithCancelCause(r.Context())
		timer := time.AfterFunc(s.askTimeout, func() { cancel(errAskTimeout) })
		res := make(chan Decision, 1)
		go func() { res <- s.ui.Ask(ctx, ask) }()
		select {
		case dec = <-res:
		case <-ctx.Done():
		}
		timer.Stop()
		s.pending.Add(-1)
		if ctx.Err() != nil {
			cause := context.Cause(ctx)
			cancel(nil)
			if errors.Is(cause, errAskTimeout) {
				s.ui.Notice("Request expired", fmt.Sprintf("%s from %s wasn't accepted in time.", capitalize(desc), sender.Name))
				writeJSON(w, 408, map[string]any{"accepted": false, "reason": "timeout"})
			} else {
				s.ui.Notice(sender.Name+" cancelled", capitalize(desc)+" won't be sent.")
			}
			return
		}
		cancel(nil)
		if !dec.Accept {
			writeJSON(w, 403, map[string]any{"accepted": false, "reason": "declined"})
			return
		}
	}

	reply := map[string]any{"accepted": true}
	if dec.Trust || pair {
		reply["trustKey"] = s.cfg.Trust(sender.ID, sender.Name, sender.Model)
	}
	if pair {
		logf("paired with %s", sender.Name)
		writeJSON(w, 200, reply)
		return
	}
	if len(items) == 0 {
		s.noteClip(text)
		go s.ui.Text(ask, text)
		writeJSON(w, 200, reply)
		return
	}
	sess := &Session{
		ID: randHex(16), Token: randHex(32), Ask: ask, SaveDir: st.SaveDir,
		started: time.Now(), rateAt: time.Now(), done: make(chan struct{}),
	}
	for _, it := range items {
		sess.items = append(sess.items, &sessItem{Item: it})
	}
	sess.lastActivity.Store(time.Now().UnixNano())
	s.mu.Lock()
	s.sessions[sess.ID] = sess
	s.mu.Unlock()
	logf("accepted %s from %s (auto=%v)", desc, sender.Name, auto)
	go s.ui.Started(ask, sess)
	reply["session"] = sess.ID
	reply["token"] = sess.Token
	writeJSON(w, 200, reply)
}

var errAskTimeout = errors.New("timeout")

// ---------------------------------------------------------------- upload

func (s *Server) authSession(r *http.Request) *Session {
	id := r.URL.Query().Get("session")
	s.mu.Lock()
	sess := s.sessions[id]
	s.mu.Unlock()
	if sess == nil {
		return nil
	}
	tok := r.Header.Get("X-Flick-Token")
	if len(tok) != len(sess.Token) || subtle.ConstantTimeCompare([]byte(tok), []byte(sess.Token)) != 1 {
		return nil
	}
	return sess
}

func (s *Server) handleUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPut {
		writeJSON(w, 405, map[string]string{"error": "method"})
		return
	}
	sess := s.authSession(r)
	if sess == nil {
		writeJSON(w, 403, map[string]string{"error": "bad session"})
		return
	}
	idx, err := strconv.Atoi(r.URL.Query().Get("index"))
	sess.mu.Lock()
	if sess.state != 0 {
		sess.mu.Unlock()
		writeJSON(w, 410, map[string]string{"error": "session closed"})
		return
	}
	if err != nil || idx < 0 || idx >= len(sess.items) {
		sess.mu.Unlock()
		writeJSON(w, 400, map[string]string{"error": "bad index"})
		return
	}
	it := sess.items[idx]
	if it.state != itemPending {
		sess.mu.Unlock()
		writeJSON(w, 409, map[string]string{"error": "already sent"})
		return
	}
	it.state = itemReceiving
	rc := http.NewResponseController(w)
	it.abort = func() { _ = rc.SetReadDeadline(time.Now()) }
	sess.mu.Unlock()

	fail := func(status int, reason string, tmp string) {
		if tmp != "" {
			_ = os.Remove(tmp)
		}
		sess.mu.Lock()
		it.abort = nil
		if it.state == itemReceiving {
			it.state = itemFailed
		}
		sess.mu.Unlock()
		s.failSession(sess, reason)
		writeJSON(w, status, map[string]string{"error": reason})
	}

	if err := os.MkdirAll(sess.SaveDir, 0o755); err != nil {
		fail(500, "write-failed", "")
		return
	}
	tmp := filepath.Join(sess.SaveDir, ".flick-"+randHex(6)+".part")
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	if err != nil {
		fail(500, "write-failed", "")
		return
	}
	buf := make([]byte, 256<<10)
	var rerr, werr error
	for {
		_ = rc.SetReadDeadline(time.Now().Add(readStall))
		n, err := r.Body.Read(buf)
		if n > 0 {
			if _, e := f.Write(buf[:n]); e != nil {
				werr = e
				break
			}
			sess.received.Add(int64(n))
			sess.lastActivity.Store(time.Now().UnixNano())
		}
		if sess.failed() {
			rerr = errors.New("cancelled")
			break
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			rerr = err
			break
		}
	}
	_ = rc.SetReadDeadline(time.Time{})
	cerr := f.Close()
	if werr != nil || (cerr != nil && rerr == nil) {
		fail(500, "write-failed", tmp)
		return
	}
	if rerr != nil {
		fail(400, "interrupted", tmp)
		return
	}
	final, err := reserveUnique(sess.SaveDir, it.Name)
	if err == nil {
		err = os.Rename(tmp, final)
	}
	if err != nil {
		fail(500, "write-failed", tmp)
		return
	}
	sess.mu.Lock()
	it.abort = nil
	it.state = itemDone
	sess.saved = append(sess.saved, final)
	allDone := true
	for _, x := range sess.items {
		if x.state != itemDone {
			allDone = false
		}
	}
	sess.mu.Unlock()
	writeJSON(w, 200, map[string]any{"ok": true, "name": filepath.Base(final)})
	if allDone {
		s.finishSession(sess)
	}
}

func (s *Server) finishSession(sess *Session) {
	sess.mu.Lock()
	if sess.state != 0 {
		sess.mu.Unlock()
		return
	}
	sess.state = 1
	sess.finishedAt = time.Now()
	sess.mu.Unlock()
	s.mu.Lock()
	delete(s.sessions, sess.ID)
	s.mu.Unlock()
	close(sess.done)
	logf("received %s from %s", sess.Ask.Description, sess.Ask.Sender.Name)
	go s.ui.Finished(sess.Ask, sess)
}

func (s *Server) failSession(sess *Session, reason string) {
	sess.mu.Lock()
	if sess.state != 0 {
		sess.mu.Unlock()
		return
	}
	sess.state = 2
	sess.reason = reason
	for _, it := range sess.items {
		if it.abort != nil {
			it.abort() // unblocks the reader; its handler removes the temp file
		}
	}
	sess.mu.Unlock()
	s.mu.Lock()
	delete(s.sessions, sess.ID)
	s.mu.Unlock()
	close(sess.done)
	logf("transfer from %s failed: %s", sess.Ask.Sender.Name, reason)
	go s.ui.Finished(sess.Ask, sess)
}

// CancelSession is called when the person presses Cancel on the computer.
func (s *Server) CancelSession(sess *Session) { s.failSession(sess, "cancelled-here") }

func (s *Server) handleCancel(w http.ResponseWriter, r *http.Request) {
	_, _ = io.Copy(io.Discard, io.LimitReader(r.Body, 4096))
	sess := s.authSession(r)
	if sess == nil {
		writeJSON(w, 403, map[string]string{"error": "bad session"})
		return
	}
	s.failSession(sess, "cancelled")
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (s *Server) sweep() {
	for range time.Tick(15 * time.Second) {
		s.mu.Lock()
		var stale []*Session
		for _, sess := range s.sessions {
			sess.mu.Lock()
			busy := false
			for _, it := range sess.items {
				if it.state == itemReceiving {
					busy = true
				}
			}
			sess.mu.Unlock()
			if !busy && time.Since(time.Unix(0, sess.lastActivity.Load())) > sessionIdle {
				stale = append(stale, sess)
			}
		}
		s.mu.Unlock()
		for _, sess := range stale {
			s.failSession(sess, "timeout")
		}
	}
}

// ---------------------------------------------------------------- local control (second launch)

func (s *Server) handleLocal(w http.ResponseWriter, r *http.Request) {
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	ip := net.ParseIP(host)
	tok := r.Header.Get("X-Flick-Local")
	if ip == nil || !ip.IsLoopback() || len(tok) != len(s.LocalToken) ||
		subtle.ConstantTimeCompare([]byte(tok), []byte(s.LocalToken)) != 1 {
		writeJSON(w, 404, map[string]string{"error": "not found"})
		return
	}
	exe, _ := os.Executable()
	switch r.URL.Path {
	case "/v1/local/status":
		writeJSON(w, 200, map[string]any{"version": Version, "exe": exe, "pid": os.Getpid(), "name": s.cfg.DisplayName()})
	case "/v1/local/show":
		s.cfg.Reload()
		go s.ui.ShowControl()
		writeJSON(w, 200, map[string]bool{"ok": true})
	case "/v1/local/send":
		var body struct {
			Paths []string `json:"paths"`
		}
		if json.NewDecoder(io.LimitReader(r.Body, 4<<20)).Decode(&body) != nil || len(body.Paths) == 0 {
			writeJSON(w, 400, map[string]string{"error": "no paths"})
			return
		}
		s.QueueSend(body.Paths)
		writeJSON(w, 200, map[string]bool{"ok": true})
	case "/v1/local/quit":
		writeJSON(w, 200, map[string]bool{"ok": true})
		go func() {
			time.Sleep(200 * time.Millisecond)
			s.ui.Quit()
			os.Exit(0)
		}()
	default:
		writeJSON(w, 404, map[string]string{"error": "not found"})
	}
}
