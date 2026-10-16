package main

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// headlessUI is used by automated tests. The "person at the computer" is
// scripted from the sender's name:
//
//	contains "decline" -> Decline   contains "trust" -> Accept + Always accept
//	contains "ignore"  -> never answers   anything else -> Accept
type headlessUI struct {
	mu  sync.Mutex
	enc *json.Encoder
}

func (h *headlessUI) emit(v map[string]any) {
	h.mu.Lock()
	defer h.mu.Unlock()
	_ = h.enc.Encode(v)
}

func (h *headlessUI) Attach(*Server) {}

func (h *headlessUI) Ask(ctx context.Context, a *Ask) Decision {
	items := []map[string]any{}
	for _, it := range a.Items {
		items = append(items, map[string]any{"name": it.Name, "size": it.Size, "mime": it.Mime})
	}
	h.emit(map[string]any{"ev": "ask", "name": a.Sender.Name, "description": a.Description, "items": items,
		"text": a.Text, "hasThumb": len(a.Thumb) > 0, "trusted": a.Trusted})
	n := strings.ToLower(a.Sender.Name)
	if strings.Contains(n, "ignore") {
		<-ctx.Done()
		reason := "sender-left"
		if context.Cause(ctx) == errAskTimeout {
			reason = "timeout"
		}
		h.emit(map[string]any{"ev": "ask-aborted", "reason": reason})
		return Decision{}
	}
	select {
	case <-ctx.Done():
		return Decision{}
	case <-time.After(150 * time.Millisecond):
	}
	if strings.Contains(n, "decline") {
		return Decision{}
	}
	return Decision{Accept: true, Trust: strings.Contains(n, "trust")}
}

func (h *headlessUI) Started(a *Ask, s *Session) {
	if a.Trusted {
		h.emit(map[string]any{"ev": "auto-accepted", "name": a.Sender.Name})
	}
}

func (h *headlessUI) Finished(a *Ask, s *Session) {
	ok, reason, files := s.Result()
	if ok {
		names := []string{}
		for _, f := range files {
			names = append(names, filepath.Base(f))
		}
		h.emit(map[string]any{"ev": "done", "files": names, "bytes": s.received.Load()})
	} else {
		h.emit(map[string]any{"ev": "failed", "reason": reason})
	}
}

func (h *headlessUI) Text(a *Ask, text string) { h.emit(map[string]any{"ev": "text", "text": text}) }
func (h *headlessUI) Notice(title, body string) {
	h.emit(map[string]any{"ev": "notice", "title": title, "body": body})
}
func (h *headlessUI) ShowControl() { h.emit(map[string]any{"ev": "show"}) }

func (h *headlessUI) PhonesChanged() {}
func (h *headlessUI) PickPhone(phones []PhoneInfo, desc string) string {
	if len(phones) == 0 {
		return ""
	}
	return phones[0].ID
}
func (h *headlessUI) NoPhones(desc string) {
	h.emit(map[string]any{"ev": "no-phones", "description": desc})
}
func (h *headlessUI) OfferStarted(o *Offer) {
	h.emit(map[string]any{"ev": "offer", "id": o.ID, "phone": o.PhoneName, "description": o.Description})
}
func (h *headlessUI) OfferFinished(o *Offer) {
	state, reason := o.State()
	h.emit(map[string]any{"ev": "offer-" + state, "id": o.ID, "reason": reason, "sent": o.sent.Load()})
}
func (h *headlessUI) ClipFromPhone(phone, text string) {
	lastPhoneClip.Store(text)
	h.emit(map[string]any{"ev": "phone-clip", "phone": phone, "text": text})
}
func (h *headlessUI) Run()  { select {} }
func (h *headlessUI) Quit() {}

// runTest starts a receiver with scripted answers, for scripts/e2e.sh.
func runTest() {
	data := os.Getenv("FLICK_DATA")
	if data == "" {
		data, _ = os.MkdirTemp("", "flick-data-")
	}
	save := os.Getenv("FLICK_SAVE")
	if save == "" {
		save, _ = os.MkdirTemp("", "flick-recv-")
	}
	cfg := loadConfig(data)
	cfg.Update(func(s *Settings) { s.Name = "Test Receiver"; s.SaveDir = save })
	cert, fp, err := loadOrCreateCert(data, cfg.Get().ID)
	if err != nil {
		panic(err)
	}
	ui := &headlessUI{enc: json.NewEncoder(os.Stdout)}
	srv := newServer(cfg, cert, fp, ui)
	srv.testMode = true
	if ms, err := strconv.Atoi(os.Getenv("FLICK_ASK_TIMEOUT")); err == nil && ms > 0 {
		srv.askTimeout = time.Duration(ms) * time.Millisecond
	}
	if err := srv.Start(); err != nil {
		panic(err)
	}
	go runDiscovery(srv)
	ui.emit(map[string]any{"ev": "ready", "port": srv.Port, "fp": fp, "id": cfg.Get().ID, "saveDir": save, "dataDir": data, "pid": os.Getpid()})
	ui.Run()
}

var testOffers sync.Map // offer ID -> *Offer, kept after they finish (test mode only)
var lastPhoneClip atomic.Value

// handleTest exposes hooks the end-to-end tests use to play the computer's side.
func (s *Server) handleTest(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	switch r.URL.Path {
	case "/v1/test/clip":
		s.ClipboardChanged(q.Get("text"))
		writeJSON(w, 200, map[string]any{"phones": len(s.Phones())})
	case "/v1/test/lastclip":
		v, _ := lastPhoneClip.Load().(string)
		writeJSON(w, 200, map[string]string{"text": v})
	case "/v1/test/share":
		on := q.Get("on") == "1"
		s.cfg.Update(func(x *Settings) { x.ShareClipboard = on })
		writeJSON(w, 200, map[string]bool{"ok": true})
	case "/v1/test/phones":
		writeJSON(w, 200, s.Phones())
	case "/v1/test/offer":
		if r.Method == http.MethodPost {
			o, err := s.StartOffer(q.Get("phone"), collectItems(q["path"]))
			if err != nil {
				writeJSON(w, 409, map[string]string{"error": err.Error()})
				return
			}
			testOffers.Store(o.ID, o)
			writeJSON(w, 200, map[string]string{"id": o.ID})
			return
		}
		v, ok := testOffers.Load(q.Get("id"))
		if !ok {
			writeJSON(w, 404, map[string]string{"error": "unknown"})
			return
		}
		o := v.(*Offer)
		state, reason := o.State()
		writeJSON(w, 200, map[string]any{"state": state, "reason": reason, "sent": o.sent.Load()})
	case "/v1/test/cancel":
		if v, ok := testOffers.Load(q.Get("id")); ok {
			s.CancelOffer(v.(*Offer))
		}
		writeJSON(w, 200, map[string]bool{"ok": true})
	default:
		writeJSON(w, 404, map[string]string{"error": "not found"})
	}
}
