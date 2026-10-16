package main

// Two-way features:
//   * Paired phones keep a live connection open (GET /v1/link) and receive
//     events over it: clipboard text and file offers from this computer.
//   * Files offered by the computer are downloaded by the phone over the same
//     pinned HTTPS server, so nothing new has to be opened in any firewall.

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	linkPing        = 25 * time.Second
	maxClipText     = 100_000
	offerAnswerWait = 2 * time.Minute
	offerIdle       = 90 * time.Second
	maxOfferFiles   = 2000
)

// ---------------------------------------------------------------- phones

type Link struct {
	PhoneID string
	Name    string
	Model   string
	Since   time.Time
	events  chan []byte
	closed  chan struct{}
	once    sync.Once
}

func (l *Link) close() { l.once.Do(func() { close(l.closed) }) }

type PhoneInfo struct {
	ID, Name, Model string
}

// phoneAuth checks the X-Flick-Phone / X-Flick-Key headers against trusted phones.
func (s *Server) phoneAuth(r *http.Request) (string, bool) {
	id := r.Header.Get("X-Flick-Phone")
	key := r.Header.Get("X-Flick-Key")
	if id == "" || !s.cfg.IsTrusted(id, key) {
		return "", false
	}
	return id, true
}

// Phones lists phones connected right now, by name.
func (s *Server) Phones() []PhoneInfo {
	s.linkMu.Lock()
	defer s.linkMu.Unlock()
	out := make([]PhoneInfo, 0, len(s.links))
	for _, l := range s.links {
		out = append(out, PhoneInfo{ID: l.PhoneID, Name: l.Name, Model: l.Model})
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out
}

func (s *Server) push(phoneID string, v any) bool {
	b, err := json.Marshal(v)
	if err != nil {
		return false
	}
	s.linkMu.Lock()
	l := s.links[phoneID]
	s.linkMu.Unlock()
	if l == nil {
		return false
	}
	select {
	case l.events <- b:
		return true
	default:
		return false // phone isn't keeping up; it will reconnect
	}
}

// DisconnectAll drops every phone link (e.g. after "Forget trusted phones").
func (s *Server) DisconnectAll() {
	s.linkMu.Lock()
	for _, l := range s.links {
		l.close()
	}
	s.linkMu.Unlock()
}

func (s *Server) handleLink(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, map[string]string{"error": "method"})
		return
	}
	id, ok := s.phoneAuth(r)
	if !ok {
		writeJSON(w, 403, map[string]string{"error": "not paired"})
		return
	}
	q := r.URL.Query()
	l := &Link{
		PhoneID: id,
		Name:    cleanStr(q.Get("name"), 60),
		Model:   cleanStr(q.Get("model"), 80),
		Since:   time.Now(),
		events:  make(chan []byte, 32),
		closed:  make(chan struct{}),
	}
	if l.Name == "" {
		l.Name = "Android phone"
	}
	if t, ok := s.cfg.Get().Trusted[id]; ok && t.Name != l.Name {
		s.cfg.Update(func(x *Settings) { t.Name = l.Name; x.Trusted[id] = t })
	}
	s.linkMu.Lock()
	if old := s.links[id]; old != nil {
		old.close()
	}
	s.links[id] = l
	s.linkMu.Unlock()
	logf("phone connected: %s", l.Name)
	defer func() {
		s.linkMu.Lock()
		if s.links[id] == l {
			delete(s.links, id)
		}
		s.linkMu.Unlock()
		l.close()
		logf("phone disconnected: %s", l.Name)
		go s.ui.PhonesChanged()
	}()
	go s.ui.PhonesChanged()

	rc := http.NewResponseController(w)
	w.Header().Set("Content-Type", "application/x-ndjson")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(200)
	send := func(b []byte) bool {
		_ = rc.SetWriteDeadline(time.Now().Add(20 * time.Second))
		if _, err := w.Write(append(b, '\n')); err != nil {
			return false
		}
		return rc.Flush() == nil
	}
	hello, _ := json.Marshal(map[string]any{"type": "hello", "id": s.cfg.Get().ID, "name": s.cfg.DisplayName(),
		"clipboard": s.cfg.Get().ShareClipboard})
	if !send(hello) {
		return
	}
	ping := []byte(`{"type":"ping"}`)
	t := time.NewTicker(linkPing)
	defer t.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-l.closed:
			return
		case b := <-l.events:
			if !send(b) {
				return
			}
		case <-t.C:
			if !send(ping) {
				return
			}
		}
	}
}

// ---------------------------------------------------------------- clipboard

// ClipboardChanged is called by the platform clipboard watcher.
func (s *Server) ClipboardChanged(text string) {
	if strings.TrimSpace(text) == "" || len(text) > maxClipText || !s.cfg.Get().ShareClipboard {
		return
	}
	s.clipMu.Lock()
	if text == s.lastClip {
		s.clipMu.Unlock()
		return
	}
	s.lastClip = text
	s.clipMu.Unlock()
	n := 0
	for _, p := range s.Phones() {
		if s.push(p.ID, map[string]any{"type": "clip", "text": text}) {
			n++
		}
	}
	if n > 0 {
		logf("clipboard sent to %d phone(s)", n)
	}
}

// handleClip receives text copied on a paired phone: it goes on this
// computer's clipboard and on the other connected phones.
func (s *Server) handleClip(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]string{"error": "method"})
		return
	}
	id, ok := s.phoneAuth(r)
	if !ok {
		writeJSON(w, 403, map[string]string{"error": "not paired"})
		return
	}
	var body struct {
		Text string `json:"text"`
	}
	if json.NewDecoder(io.LimitReader(r.Body, 4*maxClipText)).Decode(&body) != nil {
		writeJSON(w, 400, map[string]string{"error": "bad json"})
		return
	}
	text := strings.ToValidUTF8(body.Text, "")
	if strings.TrimSpace(text) == "" || len(text) > maxClipText {
		writeJSON(w, 400, map[string]string{"error": "empty or too long"})
		return
	}
	if !s.cfg.Get().ShareClipboard {
		writeJSON(w, 409, map[string]string{"error": "clipboard sharing is off on this computer"})
		return
	}
	name := "Your phone"
	if t, ok := s.cfg.Get().Trusted[id]; ok && t.Name != "" {
		name = t.Name
	}
	s.noteClip(text) // so the clipboard watcher doesn't send it straight back
	s.ui.ClipFromPhone(name, text)
	for _, p := range s.Phones() {
		if p.ID != id {
			s.push(p.ID, map[string]any{"type": "clip", "text": text})
		}
	}
	logf("clipboard from %s", name)
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// noteClip records text that came *from* a phone, so copying it here
// doesn't bounce straight back.
func (s *Server) noteClip(text string) {
	s.clipMu.Lock()
	s.lastClip = text
	s.clipMu.Unlock()
}

// ---------------------------------------------------------------- offers (computer -> phone)

type offerItem struct {
	Path string
	Name string
	Size int64
	Mime string
}

type Offer struct {
	ID          string
	PhoneID     string
	PhoneName   string
	Items       []offerItem
	Total       int64
	Description string

	mu        sync.Mutex
	state     string // waiting, accepted, done, declined, failed, cancelled
	reason    string
	rateAt    time.Time
	rateBytes int64
	rate      float64
	filesDone map[int]bool

	sent         atomic.Int64
	lastActivity atomic.Int64
	done         chan struct{}
}

func (o *Offer) Done() <-chan struct{} { return o.done }

func (o *Offer) State() (string, string) {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.state, o.reason
}

func (o *Offer) Progress() Progress {
	o.mu.Lock()
	defer o.mu.Unlock()
	now := time.Now()
	got := o.sent.Load()
	if el := now.Sub(o.rateAt); el >= 700*time.Millisecond {
		inst := float64(got-o.rateBytes) / el.Seconds()
		if o.rate == 0 {
			o.rate = inst
		} else {
			o.rate = o.rate*0.5 + inst*0.5
		}
		o.rateAt, o.rateBytes = now, got
	}
	return Progress{Received: got, Total: o.Total, Rate: o.rate, FilesDone: len(o.filesDone), Count: len(o.Items)}
}

var extraMime = map[string]string{
	".mp4": "video/mp4", ".m4v": "video/mp4", ".mov": "video/quicktime", ".mkv": "video/x-matroska",
	".avi": "video/x-msvideo", ".webm": "video/webm", ".3gp": "video/3gpp",
	".heic": "image/heic", ".heif": "image/heif", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
	".png": "image/png", ".gif": "image/gif", ".webp": "image/webp", ".bmp": "image/bmp", ".tif": "image/tiff", ".tiff": "image/tiff",
	".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".aac": "audio/aac", ".wav": "audio/wav", ".flac": "audio/flac", ".ogg": "audio/ogg",
	".pdf": "application/pdf", ".zip": "application/zip", ".txt": "text/plain", ".csv": "text/csv",
	".doc": "application/msword", ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
	".xls": "application/vnd.ms-excel", ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
	".ppt": "application/vnd.ms-powerpoint", ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
	".apk": "application/vnd.android.package-archive", ".ai": "application/postscript", ".psd": "image/vnd.adobe.photoshop",
	".svg": "image/svg+xml", ".json": "application/json",
}

func mimeOf(name string) string {
	ext := strings.ToLower(filepath.Ext(name))
	if m, ok := extraMime[ext]; ok {
		return m
	}
	if m := mime.TypeByExtension(ext); m != "" {
		return strings.SplitN(m, ";", 2)[0]
	}
	return "application/octet-stream"
}

// collectItems turns paths (files or folders) into a flat list of files.
func collectItems(paths []string) []offerItem {
	var items []offerItem
	seen := map[string]bool{}
	add := func(p string, info os.FileInfo) {
		if len(items) >= maxOfferFiles || seen[p] || !info.Mode().IsRegular() {
			return
		}
		seen[p] = true
		items = append(items, offerItem{Path: p, Name: safeName(info.Name()), Size: info.Size(), Mime: mimeOf(info.Name())})
	}
	for _, p := range paths {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		info, err := os.Stat(p)
		if err != nil {
			continue
		}
		if info.IsDir() {
			_ = filepath.Walk(p, func(q string, fi os.FileInfo, err error) error {
				if err != nil {
					return nil
				}
				if fi.IsDir() && strings.HasPrefix(fi.Name(), ".") && q != p {
					return filepath.SkipDir
				}
				if !fi.IsDir() && !strings.HasPrefix(fi.Name(), ".") {
					add(q, fi)
				}
				return nil
			})
			continue
		}
		add(p, info)
	}
	return items
}

func toItems(o []offerItem) []Item {
	out := make([]Item, len(o))
	for i, it := range o {
		out[i] = Item{Name: it.Name, Size: it.Size, Mime: it.Mime}
	}
	return out
}

var errNotConnected = errors.New("that phone isn't connected")

// StartOffer offers files to a connected phone.
func (s *Server) StartOffer(phoneID string, items []offerItem) (*Offer, error) {
	s.linkMu.Lock()
	l := s.links[phoneID]
	s.linkMu.Unlock()
	if l == nil {
		return nil, errNotConnected
	}
	o := &Offer{ID: randHex(12), PhoneID: phoneID, PhoneName: l.Name, Items: items, state: "waiting",
		rateAt: time.Now(), done: make(chan struct{}), filesDone: map[int]bool{}}
	for _, it := range items {
		o.Total += it.Size
	}
	o.Description = describeItems(toItems(items))
	o.lastActivity.Store(time.Now().UnixNano())
	wire := make([]map[string]any, len(items))
	for i, it := range items {
		wire[i] = map[string]any{"name": it.Name, "size": it.Size, "mime": it.Mime}
	}
	s.offerMu.Lock()
	s.offers[o.ID] = o
	s.offerMu.Unlock()
	if !s.push(phoneID, map[string]any{"type": "offer", "offer": map[string]any{
		"id": o.ID, "from": s.cfg.DisplayName(), "fromId": s.cfg.Get().ID, "items": wire, "total": o.Total,
		"description": o.Description,
	}}) {
		s.finishOffer(o, "failed", "not-connected")
		return o, errNotConnected
	}
	logf("offered %s to %s", o.Description, l.Name)
	go s.ui.OfferStarted(o)
	return o, nil
}

func (s *Server) finishOffer(o *Offer, state, reason string) {
	o.mu.Lock()
	if o.state == "done" || o.state == "declined" || o.state == "failed" || o.state == "cancelled" {
		o.mu.Unlock()
		return
	}
	o.state, o.reason = state, reason
	o.mu.Unlock()
	close(o.done)
	s.offerMu.Lock()
	delete(s.offers, o.ID)
	s.offerMu.Unlock()
	logf("offer to %s: %s %s", o.PhoneName, state, reason)
	go s.ui.OfferFinished(o)
}

// CancelOffer is called when the person cancels on the computer.
func (s *Server) CancelOffer(o *Offer) {
	s.push(o.PhoneID, map[string]any{"type": "cancel", "offerId": o.ID})
	s.finishOffer(o, "cancelled", "cancelled-here")
}

func (s *Server) offerFor(r *http.Request) (*Offer, bool) {
	phone, ok := s.phoneAuth(r)
	if !ok {
		return nil, false
	}
	id := r.URL.Query().Get("offer")
	s.offerMu.Lock()
	o := s.offers[id]
	s.offerMu.Unlock()
	if o == nil || subtle.ConstantTimeCompare([]byte(o.PhoneID), []byte(phone)) != 1 {
		return nil, false
	}
	return o, true
}

func (s *Server) handleOfferFile(w http.ResponseWriter, r *http.Request) {
	o, ok := s.offerFor(r)
	if !ok {
		writeJSON(w, 403, map[string]string{"error": "unknown offer"})
		return
	}
	idx, err := strconv.Atoi(r.URL.Query().Get("index"))
	if err != nil || idx < 0 || idx >= len(o.Items) {
		writeJSON(w, 400, map[string]string{"error": "bad index"})
		return
	}
	o.mu.Lock()
	if o.state == "waiting" {
		o.state = "accepted"
	}
	state := o.state
	o.mu.Unlock()
	if state != "accepted" {
		writeJSON(w, 410, map[string]string{"error": "offer closed"})
		return
	}
	it := o.Items[idx]
	f, err := os.Open(it.Path)
	if err != nil {
		s.finishOffer(o, "failed", "file-missing")
		writeJSON(w, 404, map[string]string{"error": "file missing"})
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": "stat"})
		return
	}
	w.Header().Set("Content-Type", it.Mime)
	w.Header().Set("Content-Length", strconv.FormatInt(st.Size(), 10))
	w.WriteHeader(200)
	rc := http.NewResponseController(w)
	buf := make([]byte, 256<<10)
	for {
		select {
		case <-o.done:
			return
		default:
		}
		n, rerr := f.Read(buf)
		if n > 0 {
			_ = rc.SetWriteDeadline(time.Now().Add(60 * time.Second))
			if _, werr := w.Write(buf[:n]); werr != nil {
				return
			}
			o.sent.Add(int64(n))
			o.lastActivity.Store(time.Now().UnixNano())
		}
		if rerr == io.EOF {
			o.mu.Lock()
			o.filesDone[idx] = true
			o.mu.Unlock()
			return
		}
		if rerr != nil {
			return
		}
	}
}

func (s *Server) handleOfferStatus(w http.ResponseWriter, r *http.Request) {
	o, ok := s.offerFor(r)
	if !ok {
		writeJSON(w, 403, map[string]string{"error": "unknown offer"})
		return
	}
	var body struct {
		State  string `json:"state"`
		Reason string `json:"reason"`
	}
	_ = json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&body)
	o.lastActivity.Store(time.Now().UnixNano())
	switch body.State {
	case "accepted":
		o.mu.Lock()
		if o.state == "waiting" {
			o.state = "accepted"
		}
		o.mu.Unlock()
	case "done":
		s.finishOffer(o, "done", "")
	case "declined":
		s.finishOffer(o, "declined", "")
	case "failed", "cancelled":
		s.finishOffer(o, body.State, cleanStr(body.Reason, 60))
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// sweepOffers expires offers nobody answers, or that stall.
func (s *Server) sweepOffers() {
	for range time.Tick(5 * time.Second) {
		s.offerMu.Lock()
		var list []*Offer
		for _, o := range s.offers {
			list = append(list, o)
		}
		s.offerMu.Unlock()
		for _, o := range list {
			state, _ := o.State()
			idle := time.Since(time.Unix(0, o.lastActivity.Load()))
			s.linkMu.Lock()
			connected := s.links[o.PhoneID] != nil
			s.linkMu.Unlock()
			switch {
			case state == "waiting" && idle > offerAnswerWait:
				s.push(o.PhoneID, map[string]any{"type": "cancel", "offerId": o.ID})
				s.finishOffer(o, "failed", "no-answer")
			case state == "accepted" && idle > offerIdle:
				s.finishOffer(o, "failed", "interrupted")
			case state == "waiting" && !connected && idle > 20*time.Second:
				s.finishOffer(o, "failed", "not-connected")
			}
		}
	}
}

// ---------------------------------------------------------------- sending from this computer

// QueueSend collects paths from one or more "Send with Flick" launches that
// arrive close together (Explorer starts one process per selected file) and
// then asks where to send them.
func (s *Server) QueueSend(paths []string) {
	s.sendMu.Lock()
	defer s.sendMu.Unlock()
	s.sendQueue = append(s.sendQueue, paths...)
	if s.sendTimer != nil {
		s.sendTimer.Reset(700 * time.Millisecond)
		return
	}
	s.sendTimer = time.AfterFunc(700*time.Millisecond, func() {
		s.sendMu.Lock()
		q := s.sendQueue
		s.sendQueue = nil
		s.sendTimer = nil
		s.sendMu.Unlock()
		s.SendFiles(q)
	})
}

// SendFiles asks which phone to send to, then offers the files.
func (s *Server) SendFiles(paths []string) {
	items := collectItems(paths)
	if len(items) == 0 {
		s.ui.Notice("Nothing to send", "Flick couldn't read those files.")
		return
	}
	desc := describeItems(toItems(items))
	phones := s.Phones()
	if len(phones) == 0 {
		s.ui.NoPhones(desc)
		return
	}
	id := s.ui.PickPhone(phones, desc)
	if id == "" {
		return
	}
	if _, err := s.StartOffer(id, items); err != nil {
		s.ui.Notice("Couldn't send", fmt.Sprintf("%s: %v.", capitalize(desc), err))
	}
}

// offerResult describes how an offer ended, for notifications. ok=false and
// title="" means there's nothing worth telling (the person cancelled here).
func offerResult(o *Offer) (title, body string, ok bool) {
	state, reason := o.State()
	who := o.PhoneName
	switch state {
	case "done":
		return "Sent " + o.Description, "to " + who, true
	case "declined":
		return who + " declined", capitalize(o.Description) + " wasn't sent.", false
	case "cancelled":
		if reason == "cancelled-here" {
			return "", "", false
		}
		return "Sending cancelled", who + " cancelled.", false
	}
	switch reason {
	case "no-answer":
		return who + " didn't answer", "Nobody accepted on the phone in time.", false
	case "not-connected":
		return who + " isn't connected", "Open Flick on the phone, on the same Wi-Fi, and try again.", false
	case "file-missing":
		return "Couldn't send", "A file was moved or deleted before it was sent.", false
	case "no-space":
		return "Couldn't send", who + " doesn't have enough free space.", false
	}
	return "Sending stopped", "The connection to " + who + " was lost.", false
}

// phoneLabels gives each connected phone a unique, readable label.
func phoneLabels(phones []PhoneInfo) []string {
	count := map[string]int{}
	for _, p := range phones {
		count[p.Name]++
	}
	out := make([]string, len(phones))
	for i, p := range phones {
		out[i] = p.Name
		if count[p.Name] > 1 && p.Model != "" {
			out[i] = p.Name + " (" + p.Model + ")"
		}
	}
	return out
}

func phonesLine(phones []PhoneInfo) string {
	if len(phones) == 0 {
		return "No phone connected"
	}
	return "Connected: " + strings.Join(phoneLabels(phones), ", ")
}

// ForgetPhones removes all trusted phones and disconnects them.
func (s *Server) ForgetPhones() {
	s.cfg.ForgetTrusted()
	s.DisconnectAll()
}
