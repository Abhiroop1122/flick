//go:build windows

package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const (
	osName      = "windows"
	runKeyPath  = `Software\Microsoft\Windows\CurrentVersion\Run`
	uninstKey   = `Software\Microsoft\Windows\CurrentVersion\Uninstall\Flick`
	runValue    = "Flick"
	firewallTag = "Flick"
)

func configDir() string {
	d, err := os.UserConfigDir() // %APPDATA%
	if err != nil {
		d = os.Getenv("APPDATA")
	}
	return filepath.Join(d, "Flick")
}

func installDir() string {
	base := os.Getenv("LOCALAPPDATA")
	if base == "" {
		base = filepath.Join(os.Getenv("USERPROFILE"), "AppData", "Local")
	}
	return filepath.Join(base, "Programs", "Flick")
}

func installedExe() string { return filepath.Join(installDir(), "Flick.exe") }

func defaultSaveDir() string {
	if p, err := windows.KnownFolderPath(windows.FOLDERID_Downloads, 0); err == nil && p != "" {
		return p
	}
	h, _ := os.UserHomeDir()
	return filepath.Join(h, "Downloads")
}

func computerName() string {
	n, _ := os.Hostname()
	return n
}

func freeBytes(dir string) (uint64, bool) {
	var avail, total, free uint64
	p, err := windows.UTF16PtrFromString(dir)
	if err != nil {
		return 0, false
	}
	if err := windows.GetDiskFreeSpaceEx(p, &avail, &total, &free); err != nil {
		return 0, false
	}
	return avail, true
}

func shellOpen(target string) {
	_ = windows.ShellExecute(0, u16("open"), u16(target), nil, nil, SW_SHOWNORMAL)
}

func openURL(u string)  { shellOpen(u) }
func openPath(p string) { shellOpen(p) }

func revealPath(p string) {
	cmd := exec.Command("explorer.exe")
	cmd.SysProcAttr = &syscall.SysProcAttr{CmdLine: `explorer.exe /select,"` + p + `"`}
	_ = cmd.Start()
}

func copyText(text string) { copyTextWin(text) }

func launchedAtLogin() bool { return false }

// lockAgent: one background agent per signed-in user.
func lockAgent(dir string) (func(), bool) {
	h, err := windows.CreateMutex(nil, false, u16(`Local\FlickReceiverAgent`))
	if err != nil {
		if errors.Is(err, windows.ERROR_ALREADY_EXISTS) {
			if h != 0 {
				windows.CloseHandle(h)
			}
			return nil, false
		}
		return func() {}, true
	}
	return func() { windows.CloseHandle(h) }, true
}

// ---------------------------------------------------------------- tray

const (
	trayCallback = WM_APP + 1
	cmdOpen      = 1
	cmdFolder    = 2
	cmdReveal    = 3
	cmdForget    = 4
	cmdStartup   = 5
	cmdQuit      = 6
	cmdSend      = 7
	cmdClip      = 8
)

type winUI struct {
	cfg        *Config
	srv        *Server
	hwnd       uintptr
	smallIcon  uintptr
	bigIcon    uintptr
	taskbarMsg uint32
	trayMu     sync.Mutex
	lastMu     sync.Mutex
	lastPath   string
	wndProc    uintptr
}

func newPlatformUI(cfg *Config) UI { return &winUI{cfg: cfg} }

func (u *winUI) Attach(s *Server) { u.srv = s }

func (u *winUI) nid() notifyIconData {
	var n notifyIconData
	n.CbSize = uint32(unsafe.Sizeof(n))
	n.HWnd = u.hwnd
	n.UID = 1
	return n
}

func (u *winUI) addIcon() {
	n := u.nid()
	n.UFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP | NIF_SHOWTIP
	n.UCallbackMessage = trayCallback
	n.HIcon = u.smallIcon
	copyU16(n.SzTip[:], "Flick — visible as "+u.cfg.DisplayName())
	u.trayMu.Lock()
	defer u.trayMu.Unlock()
	pShellNotifyIconW.Call(NIM_ADD, uintptr(unsafe.Pointer(&n)))
	n.UVersion = NOTIFYICON_VERSION_4
	pShellNotifyIconW.Call(NIM_SETVERSION, uintptr(unsafe.Pointer(&n)))
}

func (u *winUI) balloon(title, text, reveal string) {
	if reveal != "" {
		u.lastMu.Lock()
		u.lastPath = reveal
		u.lastMu.Unlock()
	}
	if u.hwnd == 0 {
		return
	}
	n := u.nid()
	n.UFlags = NIF_INFO
	copyU16(n.SzInfoTitle[:], title)
	copyU16(n.SzInfo[:], text)
	n.DwInfoFlags = NIIF_USER | NIIF_LARGE_ICON
	n.HBalloonIcon = u.bigIcon
	u.trayMu.Lock()
	defer u.trayMu.Unlock()
	pShellNotifyIconW.Call(NIM_MODIFY, uintptr(unsafe.Pointer(&n)))
}

func (u *winUI) removeIcon() {
	if u.hwnd == 0 {
		return
	}
	n := u.nid()
	u.trayMu.Lock()
	defer u.trayMu.Unlock()
	pShellNotifyIconW.Call(NIM_DELETE, uintptr(unsafe.Pointer(&n)))
}

func (u *winUI) proc(hwnd, m, wParam, lParam uintptr) uintptr {
	switch uint32(m) {
	case trayCallback:
		switch uint32(lParam & 0xFFFF) {
		case WM_CONTEXTMENU, NIN_SELECT, NIN_KEYSELECT, WM_LBUTTONUP:
			u.showMenu()
		case NIN_BALLOONUSERCLICK:
			u.lastMu.Lock()
			p := u.lastPath
			u.lastMu.Unlock()
			if p != "" {
				if st, err := os.Stat(p); err == nil && st.IsDir() {
					openPath(p)
				} else {
					revealPath(p)
				}
			}
		}
		return 0
	case WM_CLIPBOARDUPDATE:
		if u.srv != nil && len(u.srv.Phones()) > 0 && u.cfg.Get().ShareClipboard {
			if text, ok := readClipboardText(hwnd); ok {
				go u.srv.ClipboardChanged(text)
			}
		}
		return 0
	case WM_DESTROY:
		pPostQuitMessage.Call(0)
		return 0
	}
	if u.taskbarMsg != 0 && uint32(m) == u.taskbarMsg { // Explorer restarted: put the icon back
		u.addIcon()
		return 0
	}
	r, _, _ := pDefWindowProcW.Call(hwnd, m, wParam, lParam)
	return r
}

func startupEnabled() bool {
	k, err := registry.OpenKey(registry.CURRENT_USER, runKeyPath, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer k.Close()
	_, _, err = k.GetStringValue(runValue)
	return err == nil
}

func setStartup(on bool) {
	k, _, err := registry.CreateKey(registry.CURRENT_USER, runKeyPath, registry.SET_VALUE|registry.QUERY_VALUE)
	if err != nil {
		return
	}
	defer k.Close()
	if on {
		_ = k.SetStringValue(runValue, `"`+installedExe()+`" --agent`)
	} else {
		_ = k.DeleteValue(runValue)
	}
}

func (u *winUI) showMenu() {
	st := u.cfg.Get()
	menu, _, _ := pCreatePopupMenu.Call()
	add := func(flags uint32, id uintptr, text string) {
		pAppendMenuW.Call(menu, uintptr(flags), id, uintptr(unsafe.Pointer(u16(text))))
	}
	add(MF_STRING|MF_GRAYED, 0, "Flick is on — visible as “"+u.cfg.DisplayName()+"”")
	add(MF_STRING|MF_GRAYED, 0, phonesLine(u.srv.Phones()))
	pAppendMenuW.Call(menu, MF_SEPARATOR, 0, 0)
	add(MF_STRING, cmdSend, "Send files to phone…")
	add(MF_STRING, cmdOpen, "Open received files")
	add(MF_STRING, cmdFolder, "Change save folder…")
	reveal := uint32(MF_STRING)
	if st.RevealWhenDone {
		reveal |= MF_CHECKED
	}
	add(reveal, cmdReveal, "Show files when they arrive")
	clip := uint32(MF_STRING)
	if st.ShareClipboard {
		clip |= MF_CHECKED
	}
	add(clip, cmdClip, "Share clipboard with phones")
	if n := len(st.Trusted); n > 0 {
		add(MF_STRING, cmdForget, fmt.Sprintf("Forget connected phones (%d)", n))
	}
	startup := uint32(MF_STRING)
	if startupEnabled() {
		startup |= MF_CHECKED
	}
	add(startup, cmdStartup, "Start with Windows")
	pAppendMenuW.Call(menu, MF_SEPARATOR, 0, 0)
	add(MF_STRING, cmdQuit, "Quit Flick")

	var pt point
	pGetCursorPos.Call(uintptr(unsafe.Pointer(&pt)))
	pSetForegroundWindow.Call(u.hwnd)
	cmd, _, _ := pTrackPopupMenu.Call(menu, TPM_RETURNCMD|TPM_NONOTIFY|TPM_RIGHTBUTTON, uintptr(pt.X), uintptr(pt.Y), 0, u.hwnd, 0)
	pPostMessageW.Call(u.hwnd, WM_NULL, 0, 0)
	pDestroyMenu.Call(menu)

	switch cmd {
	case cmdOpen:
		openPath(st.SaveDir)
	case cmdFolder:
		go func() {
			if dir := chooseFolder("Save files from your phone to:"); dir != "" {
				u.cfg.Update(func(s *Settings) { s.SaveDir = dir })
				u.balloon("Save folder changed", "Files from your phone will go to "+dir, dir)
			}
		}()
	case cmdReveal:
		u.cfg.Update(func(s *Settings) { s.RevealWhenDone = !s.RevealWhenDone })
	case cmdSend:
		go func() {
			if len(u.srv.Phones()) == 0 {
				u.NoPhones("files")
				return
			}
			if paths := chooseFiles("Choose files to send to your phone"); len(paths) > 0 {
				u.srv.SendFiles(paths)
			}
		}()
	case cmdClip:
		u.cfg.Update(func(s *Settings) { s.ShareClipboard = !s.ShareClipboard })
	case cmdForget:
		u.srv.ForgetPhones()
		u.balloon("Phones forgotten", "Phones will need to connect again before they get files or your clipboard.", "")
	case cmdStartup:
		setStartup(!startupEnabled())
	case cmdQuit:
		u.Quit()
		os.Exit(0)
	}
}

func (u *winUI) Run() {
	runtime.LockOSThread()
	u.smallIcon = loadAppIcon(false)
	u.bigIcon = loadAppIcon(true)
	u.wndProc = syscall.NewCallback(u.proc)
	className := u16("FlickTrayWindow")
	wc := wndClassEx{WndProc: u.wndProc, Instance: hInstance(), ClassName: className}
	wc.Size = uint32(unsafe.Sizeof(wc))
	pRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc)))
	hwnd, _, err := pCreateWindowExW.Call(0, uintptr(unsafe.Pointer(className)), uintptr(unsafe.Pointer(u16("Flick"))),
		0, 0, 0, 0, 0, 0, 0, hInstance(), 0)
	if hwnd == 0 {
		logf("tray window: %v", err)
		select {}
	}
	u.hwnd = hwnd
	tb, _, _ := pRegisterWindowMessageW.Call(uintptr(unsafe.Pointer(u16("TaskbarCreated"))))
	u.taskbarMsg = uint32(tb)
	u.addIcon()
	pAddClipboardFormatListen.Call(hwnd)
	var m msg
	for {
		r, _, _ := pGetMessageW.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
		if int32(r) <= 0 {
			break
		}
		pTranslateMessage.Call(uintptr(unsafe.Pointer(&m)))
		pDispatchMessageW.Call(uintptr(unsafe.Pointer(&m)))
	}
	u.removeIcon()
}

func (u *winUI) Quit() { u.removeIcon() }

// ---------------------------------------------------------------- dialogs

const (
	btnAccept  = 100
	btnDecline = 101
	btnOpenDir = 102
)

func (u *winUI) iconFlags() (uintptr, uint32) {
	if u.bigIcon != 0 {
		return u.bigIcon, TDF_USE_HICON_MAIN
	}
	return TD_INFORMATION_ICON, 0
}

func (u *winUI) Ask(ctx context.Context, a *Ask) Decision {
	st := u.cfg.Get()
	if st.PlaySound {
		pMessageBeep.Call(MB_ICONASTERISK)
	}
	who := a.Sender.Name
	if a.Pair {
		icon, iflag := u.iconFlags()
		closed := false
		btn, _ := taskDialog(&taskDialogOpts{
			Title:       "Flick",
			Instruction: who + " wants to connect to this PC",
			Content: "Once connected, it can send you files without asking, you can send files to it, " +
				"and anything you copy on this PC is ready to paste on the phone.",
			Buttons:       []tdButton{{btnAccept, "Connect"}, {btnDecline, "Don't allow"}},
			DefaultButton: btnAccept,
			Flags:         iflag | TDF_ALLOW_DIALOG_CANCELLATION | TDF_NO_SET_FOREGROUND,
			Icon:          icon,
			Corner:        true,
			OnTimer: func(hwnd uintptr) {
				if ctx.Err() != nil && !closed {
					closed = true
					tdClick(hwnd, btnDecline)
				}
			},
		})
		ok := btn == btnAccept && ctx.Err() == nil
		return Decision{Accept: ok, Trust: ok}
	}
	accept := "Accept"
	content := namesLine(a)
	if len(a.Items) == 0 {
		content = truncate(a.Text, 300)
		accept = "Copy"
		if isHTTPURL(a.Text) && st.OpenLinks {
			accept = "Open"
		}
	}
	verification := ""
	if !a.Trusted {
		verification = "Always accept from " + who
	}
	icon, iflag := u.iconFlags()
	closedByUs := false
	o := &taskDialogOpts{
		Title:         "Flick",
		Instruction:   fmt.Sprintf("%s wants to send you %s", who, a.Description),
		Content:       content,
		Verification:  verification,
		Buttons:       []tdButton{{btnAccept, accept}, {btnDecline, "Decline"}},
		DefaultButton: btnAccept,
		Flags:         iflag | TDF_ALLOW_DIALOG_CANCELLATION | TDF_NO_SET_FOREGROUND,
		Icon:          icon,
		Corner:        true,
		OnTimer: func(hwnd uintptr) {
			if ctx.Err() != nil && !closedByUs {
				closedByUs = true
				tdClick(hwnd, btnDecline)
			}
		},
	}
	btn, verified := taskDialog(o)
	if ctx.Err() != nil {
		return Decision{}
	}
	return Decision{Accept: btn == btnAccept, Trust: btn == btnAccept && verified}
}

func progressText(a *Ask, p Progress) string {
	parts := []string{}
	if p.Total > 0 {
		parts = append(parts, fmt.Sprintf("%s of %s", humanSize(p.Received), humanSize(p.Total)))
	} else {
		parts = append(parts, humanSize(p.Received))
	}
	if p.Rate > 0 {
		parts = append(parts, humanSize(int64(p.Rate))+"/s")
	}
	if p.Count > 1 {
		cur := p.FilesDone + 1
		if cur > p.Count {
			cur = p.Count
		}
		parts = append(parts, fmt.Sprintf("file %d of %d", cur, p.Count))
	}
	return strings.Join(parts, "  ·  ")
}

func (u *winUI) Started(a *Ask, s *Session) {
	select {
	case <-s.Done():
		return
	case <-time.After(800 * time.Millisecond):
	}
	icon, iflag := u.iconFlags()
	closing := false
	last := ""
	o := &taskDialogOpts{
		Title:         "Flick",
		Instruction:   fmt.Sprintf("Receiving %s from %s", a.Description, a.Sender.Name),
		Content:       progressText(a, s.Progress()),
		CommonButtons: TDCBF_CANCEL_BUTTON,
		Flags:         iflag | TDF_SHOW_PROGRESS_BAR | TDF_ALLOW_DIALOG_CANCELLATION | TDF_NO_SET_FOREGROUND | TDF_CAN_BE_MINIMIZED,
		Icon:          icon,
		Corner:        true,
		OnCreated: func(hwnd uintptr) {
			sendMsg(hwnd, TDM_SET_PROGRESS_BAR_RANGE, 0, 1000<<16)
		},
		OnTimer: func(hwnd uintptr) {
			select {
			case <-s.Done():
				if !closing {
					closing = true
					tdClick(hwnd, IDCANCEL)
				}
				return
			default:
			}
			p := s.Progress()
			if p.Total > 0 {
				sendMsg(hwnd, TDM_SET_PROGRESS_BAR_POS, uintptr(p.Received*1000/p.Total), 0)
			}
			if t := progressText(a, p); t != last {
				last = t
				tdSetText(hwnd, TDE_CONTENT, t)
			}
		},
	}
	btn, _ := taskDialog(o)
	if btn == IDCANCEL && !closing {
		u.srv.CancelSession(s)
	}
}

func (u *winUI) Finished(a *Ask, s *Session) {
	ok, reason, files := s.Result()
	st := u.cfg.Get()
	if ok {
		reveal := ""
		if len(files) > 0 {
			reveal = files[0]
		}
		u.balloon("Received "+a.Description, fmt.Sprintf("From %s · saved to %s. Click to show.", a.Sender.Name, filepath.Base(st.SaveDir)), reveal)
		if st.RevealWhenDone && reveal != "" {
			revealPath(reveal)
		}
		return
	}
	title, body := failText(a, reason)
	reveal := ""
	if len(files) > 0 {
		body += fmt.Sprintf(" %d of %d saved.", len(files), len(a.Items))
		reveal = files[0]
	}
	u.balloon(title, body, reveal)
}

func (u *winUI) Text(a *Ask, text string) {
	if isHTTPURL(text) && u.cfg.Get().OpenLinks {
		openURL(strings.TrimSpace(text))
		u.balloon("Link opened", "From "+a.Sender.Name, "")
		return
	}
	copyText(text)
	u.balloon("Copied to clipboard", "From "+a.Sender.Name, "")
}

func (u *winUI) Notice(title, body string) { u.balloon(title, body, "") }

func (u *winUI) ShowControl() {
	st := u.cfg.Get()
	icon, iflag := u.iconFlags()
	btn, _ := taskDialog(&taskDialogOpts{
		Title:       "Flick",
		Instruction: "Flick is on",
		Content: fmt.Sprintf("Your Android phone sees this PC as “%s”.\n%s.\nFiles are saved to %s.\n\n"+
			"To send files to your phone, right-click them → Send to → Phone (Flick). "+
			"For settings, click the Flick icon in the system tray (next to the clock — it may be under the ^ arrow).",
			u.cfg.DisplayName(), phonesLine(u.srv.Phones()), st.SaveDir),
		Buttons:       []tdButton{{btnOpenDir, "Open received files"}},
		CommonButtons: TDCBF_OK_BUTTON,
		DefaultButton: IDOK,
		Flags:         iflag | TDF_ALLOW_DIALOG_CANCELLATION,
		Icon:          icon,
	})
	if btn == btnOpenDir {
		openPath(st.SaveDir)
	}
}

// ---------------------------------------------------------------- sending to a phone

// ClipFromPhone: it just lands on the clipboard (and in Win+V history), silently.
func (u *winUI) ClipFromPhone(phone, text string) { copyText(text) }

func (u *winUI) PhonesChanged() {
	if u.hwnd == 0 {
		return
	}
	n := u.nid()
	n.UFlags = NIF_TIP | NIF_SHOWTIP
	tip := "Flick — visible as " + u.cfg.DisplayName()
	if p := u.srv.Phones(); len(p) > 0 {
		tip = "Flick — " + phonesLine(p)
	}
	copyU16(n.SzTip[:], tip)
	u.trayMu.Lock()
	defer u.trayMu.Unlock()
	pShellNotifyIconW.Call(NIM_MODIFY, uintptr(unsafe.Pointer(&n)))
}

func (u *winUI) PickPhone(phones []PhoneInfo, desc string) string {
	labels := phoneLabels(phones)
	var buttons []tdButton
	for i, l := range labels {
		note := "Connected"
		if phones[i].Model != "" && !strings.Contains(l, phones[i].Model) {
			note = phones[i].Model + " · connected"
		}
		buttons = append(buttons, tdButton{int32(300 + i), l + "\n" + note})
	}
	icon, iflag := u.iconFlags()
	btn, _ := taskDialog(&taskDialogOpts{
		Title:         "Flick",
		Instruction:   "Send " + desc + " to…",
		Buttons:       buttons,
		CommonButtons: TDCBF_CANCEL_BUTTON,
		Flags:         iflag | TDF_USE_COMMAND_LINKS | TDF_ALLOW_DIALOG_CANCELLATION,
		Icon:          icon,
	})
	if i := int(btn) - 300; i >= 0 && i < len(phones) {
		return phones[i].ID
	}
	return ""
}

func (u *winUI) NoPhones(desc string) {
	icon, iflag := u.iconFlags()
	taskDialog(&taskDialogOpts{
		Title:       "Flick",
		Instruction: "No phone is connected",
		Content: "To connect your phone: open Flick on it (on the same Wi-Fi), find this PC under “Your computers” and tap Connect. " +
			"After that it stays connected in the background.",
		CommonButtons: TDCBF_OK_BUTTON,
		Flags:         iflag | TDF_ALLOW_DIALOG_CANCELLATION,
		Icon:          icon,
	})
}

func offerProgressText(o *Offer, p Progress) string {
	parts := []string{fmt.Sprintf("%s of %s", humanSize(p.Received), humanSize(p.Total))}
	if p.Rate > 0 {
		parts = append(parts, humanSize(int64(p.Rate))+"/s")
	}
	if p.Count > 1 {
		parts = append(parts, fmt.Sprintf("%d of %d done", p.FilesDone, p.Count))
	}
	return strings.Join(parts, "  ·  ")
}

func (u *winUI) OfferStarted(o *Offer) {
	icon, iflag := u.iconFlags()
	closing, marquee := false, true
	last := ""
	btn, _ := taskDialog(&taskDialogOpts{
		Title:         "Flick",
		Instruction:   "Sending " + o.Description + " to " + o.PhoneName,
		Content:       "Waiting for " + o.PhoneName + " to accept…",
		CommonButtons: TDCBF_CANCEL_BUTTON,
		Flags:         iflag | TDF_SHOW_MARQUEE_PROGRESS_BAR | TDF_ALLOW_DIALOG_CANCELLATION | TDF_NO_SET_FOREGROUND | TDF_CAN_BE_MINIMIZED,
		Icon:          icon,
		Corner:        true,
		OnCreated: func(hwnd uintptr) {
			sendMsg(hwnd, TDM_SET_PROGRESS_BAR_MARQUEE, 1, 30)
		},
		OnTimer: func(hwnd uintptr) {
			select {
			case <-o.Done():
				if !closing {
					closing = true
					tdClick(hwnd, IDCANCEL)
				}
				return
			default:
			}
			if state, _ := o.State(); state != "accepted" {
				return
			}
			if marquee {
				marquee = false
				sendMsg(hwnd, TDM_SET_MARQUEE_PROGRESS_BAR, 0, 0)
				sendMsg(hwnd, TDM_SET_PROGRESS_BAR_RANGE, 0, 1000<<16)
			}
			p := o.Progress()
			if p.Total > 0 {
				sendMsg(hwnd, TDM_SET_PROGRESS_BAR_POS, uintptr(p.Received*1000/p.Total), 0)
			}
			if t := offerProgressText(o, p); t != last {
				last = t
				tdSetText(hwnd, TDE_CONTENT, t)
			}
		},
	})
	if btn == IDCANCEL && !closing {
		u.srv.CancelOffer(o)
	}
}

func (u *winUI) OfferFinished(o *Offer) {
	if title, body, _ := offerResult(o); title != "" {
		u.balloon(title, body, "")
	}
}

func failText(a *Ask, reason string) (string, string) {
	who := a.Sender.Name
	switch reason {
	case "cancelled-here":
		return "Transfer cancelled", "You cancelled the transfer."
	case "cancelled":
		return "Transfer cancelled", who + " cancelled the transfer."
	case "write-failed":
		return "Couldn't save files", "Check the save folder (right-click the Flick tray icon)."
	case "timeout":
		return "Transfer stopped", who + " stopped sending."
	}
	return "Transfer failed", "The connection was lost. Keep both devices on the same Wi-Fi."
}

func platformAgentStarted(s *Server) {}

// ---------------------------------------------------------------- install

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp := dst + ".new"
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		os.Remove(tmp)
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	return os.Rename(tmp, dst)
}

func samePath(a, b string) bool {
	ca, _ := filepath.Abs(a)
	cb, _ := filepath.Abs(b)
	return strings.EqualFold(filepath.Clean(ca), filepath.Clean(cb))
}

// killOldInstances stops other Flick.exe processes (e.g. the earlier Electron version).
func killOldInstances(keep string) {
	snap, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		return
	}
	defer windows.CloseHandle(snap)
	var e windows.ProcessEntry32
	e.Size = uint32(unsafe.Sizeof(e))
	me := uint32(os.Getpid())
	for err := windows.Process32First(snap, &e); err == nil; err = windows.Process32Next(snap, &e) {
		if !strings.EqualFold(windows.UTF16ToString(e.ExeFile[:]), "Flick.exe") || e.ProcessID == me {
			continue
		}
		h, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION|windows.PROCESS_TERMINATE, false, e.ProcessID)
		if err != nil {
			continue
		}
		buf := make([]uint16, 1024)
		n := uint32(len(buf))
		path := ""
		if windows.QueryFullProcessImageName(h, 0, &buf[0], &n) == nil {
			path = windows.UTF16ToString(buf[:n])
		}
		if path != "" && !samePath(path, keep) {
			_ = windows.TerminateProcess(h, 0)
		}
		windows.CloseHandle(h)
	}
}

func writeUninstallEntry(exe string) {
	k, _, err := registry.CreateKey(registry.CURRENT_USER, uninstKey, registry.ALL_ACCESS)
	if err != nil {
		return
	}
	defer k.Close()
	_ = k.SetStringValue("DisplayName", "Flick")
	_ = k.SetStringValue("DisplayIcon", exe+",0")
	_ = k.SetStringValue("DisplayVersion", Version)
	_ = k.SetStringValue("Publisher", "Piqit Design")
	_ = k.SetStringValue("InstallLocation", filepath.Dir(exe))
	_ = k.SetStringValue("UninstallString", `"`+exe+`" --uninstall`)
	_ = k.SetDWordValue("NoModify", 1)
	_ = k.SetDWordValue("NoRepair", 1)
	if st, err := os.Stat(exe); err == nil {
		_ = k.SetDWordValue("EstimatedSize", uint32(st.Size()/1024))
	}
}

func startMenuShortcut() string {
	return filepath.Join(os.Getenv("APPDATA"), `Microsoft\Windows\Start Menu\Programs\Flick.lnk`)
}

func createShortcut(exe string) {
	q := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }
	script := "$s=(New-Object -ComObject WScript.Shell).CreateShortcut(" + q(startMenuShortcut()) + ");" +
		"$s.TargetPath=" + q(exe) + ";$s.Description='Receive files from your Android phone';$s.Save()"
	cmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000 /* CREATE_NO_WINDOW */}
	_ = cmd.Run()
}

func sendToShortcut() string {
	return filepath.Join(os.Getenv("APPDATA"), `Microsoft\Windows\SendTo\Phone (Flick).lnk`)
}

// createSendTo adds "Send to → Phone (Flick)" to Explorer's right-click menu.
func createSendTo(exe string) {
	q := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }
	script := "$s=(New-Object -ComObject WScript.Shell).CreateShortcut(" + q(sendToShortcut()) + ");" +
		"$s.TargetPath=" + q(exe) + ";$s.Arguments='--send';$s.IconLocation=" + q(exe+",0") + ";" +
		"$s.Description='Send to your phone with Flick';$s.Save()"
	cmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	_ = cmd.Run()
}

var verbKeys = []string{`Software\Classes\*\shell\FlickSend`, `Software\Classes\Directory\shell\FlickSend`}

// writeShellVerbs adds "Send with Flick" to the right-click menu of files and folders
// (on Windows 11 it's under "Show more options").
func writeShellVerbs(exe string) {
	for _, base := range verbKeys {
		k, _, err := registry.CreateKey(registry.CURRENT_USER, base, registry.ALL_ACCESS)
		if err != nil {
			continue
		}
		_ = k.SetStringValue("", "Send with Flick")
		_ = k.SetStringValue("Icon", exe+",0")
		_ = k.SetStringValue("MultiSelectModel", "Player")
		k.Close()
		if c, _, err := registry.CreateKey(registry.CURRENT_USER, base+`\command`, registry.ALL_ACCESS); err == nil {
			_ = c.SetStringValue("", `"`+exe+`" --send "%1"`)
			c.Close()
		}
	}
}

func removeShellVerbs() {
	for _, base := range verbKeys {
		_ = registry.DeleteKey(registry.CURRENT_USER, base+`\command`)
		_ = registry.DeleteKey(registry.CURRENT_USER, base)
	}
	_ = os.Remove(sendToShortcut())
}

func firewallParams(exe string) string {
	return `/c netsh advfirewall firewall delete rule name=` + firewallTag + ` >nul 2>&1 & ` +
		`netsh advfirewall firewall add rule name=` + firewallTag + ` dir=in action=allow program="` + exe +
		`" enable=yes profile=any description="Receive files from your Android phone with Flick"`
}

func startAgent(exe string) error {
	cmd := exec.Command(exe, "--agent")
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x00000008 | 0x00000200} // DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
	if err := cmd.Start(); err != nil {
		return err
	}
	return cmd.Process.Release()
}

func platformInstall(dir string) {
	setupLogFile(dir)
	big := loadAppIcon(true)
	icon, iflag := uintptr(TD_INFORMATION_ICON), uint32(0)
	if big != 0 {
		icon, iflag = big, TDF_USE_HICON_MAIN
	}
	showError := func(msg string) {
		taskDialog(&taskDialogOpts{Title: "Flick", Instruction: "Flick couldn't be set up", Content: msg,
			CommonButtons: TDCBF_OK_BUTTON, Icon: TD_ERROR_ICON})
	}

	exe, _ := os.Executable()
	target := installedExe()
	stopAgent(dir)
	killOldInstances(exe)
	if !samePath(exe, target) {
		if err := os.MkdirAll(installDir(), 0o755); err != nil {
			showError(err.Error())
			return
		}
		var err error
		for i := 0; i < 10; i++ { // the old agent may take a moment to release the file
			if err = copyFile(exe, target); err == nil {
				break
			}
			time.Sleep(300 * time.Millisecond)
		}
		if err != nil {
			showError("Couldn't copy Flick into place: " + err.Error())
			return
		}
	}

	// Start with Windows; remove start-up entries left by the earlier version.
	setStartup(true)
	if k, err := registry.OpenKey(registry.CURRENT_USER, runKeyPath, registry.SET_VALUE); err == nil {
		_ = k.DeleteValue("in.piqit.flick")
		_ = k.DeleteValue("electron.app.Flick")
		k.Close()
	}
	writeUninstallEntry(target)
	if _, err := os.Stat(startMenuShortcut()); err != nil {
		createShortcut(target)
	}
	createSendTo(target)
	writeShellVerbs(target)

	cfg := loadConfig(dir)
	if !cfg.Get().FirewallDone {
		// One administrator prompt so phones can reach Flick on any Wi-Fi
		// (Windows treats many home networks as "Public" and blocks them).
		if err := runElevated(filepath.Join(os.Getenv("SystemRoot"), "System32", "cmd.exe"), firewallParams(target)); err == nil {
			cfg.Update(func(s *Settings) { s.FirewallDone = true })
		} else {
			logf("firewall rule: %v", err)
		}
	}

	if err := startAgent(target); err != nil {
		showError("Couldn't start Flick: " + err.Error())
		return
	}
	if !waitForAgent(dir, 10*time.Second) {
		showError("Flick was installed but didn't start. Try opening it again, or restart your PC.")
		return
	}
	taskDialog(&taskDialogOpts{
		Title:       "Flick",
		Instruction: "Flick is on",
		Content: fmt.Sprintf("Your Android phone will now see this PC as “%s”.\n\n"+
			"Flick runs quietly in the background and starts automatically with Windows — you never need to open it again.\n\n"+
			"To send files to your phone: right-click them → Send to → Phone (Flick). Text you copy on this PC is ready to paste on your connected phone.\n\n"+
			"Flick's icon lives in the system tray next to the clock (it may be under the ^ arrow). Right-click it for settings.",
			cfg.DisplayName()),
		CommonButtons: TDCBF_OK_BUTTON,
		Flags:         iflag,
		Icon:          icon,
	})
}

func platformUninstall() {
	r, _ := taskDialog(&taskDialogOpts{
		Title: "Flick", Instruction: "Remove Flick from this PC?",
		Content: "Your phone won't be able to send files to this PC any more.",
		Buttons: []tdButton{{btnAccept, "Remove"}}, CommonButtons: TDCBF_CANCEL_BUTTON, DefaultButton: IDCANCEL,
		Icon: TD_INFORMATION_ICON,
	})
	if r != btnAccept {
		return
	}
	dir := configDir()
	stopAgent(dir)
	exe, _ := os.Executable()
	killOldInstances(exe)
	setStartup(false)
	removeShellVerbs()
	_ = registry.DeleteKey(registry.CURRENT_USER, uninstKey)
	_ = os.Remove(startMenuShortcut())
	_ = runElevated(filepath.Join(os.Getenv("SystemRoot"), "System32", "cmd.exe"),
		`/c netsh advfirewall firewall delete rule name=`+firewallTag)
	cfg := loadConfig(dir)
	cfg.Update(func(s *Settings) { s.FirewallDone = false })
	taskDialog(&taskDialogOpts{Title: "Flick", Instruction: "Flick was removed", CommonButtons: TDCBF_OK_BUTTON, Icon: TD_INFORMATION_ICON})
	// Delete the program folder once this process has exited.
	cmd := exec.Command(filepath.Join(os.Getenv("SystemRoot"), "System32", "cmd.exe"))
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000,
		CmdLine: `cmd.exe /c ping 127.0.0.1 -n 3 >nul & rmdir /s /q "` + installDir() + `"`}
	_ = cmd.Start()
}

func autostartConfigured() bool { return false }

func ensureAgent(dir string) {
	if _, ok := pingAgent(dir); ok {
		return
	}
	if _, err := os.Stat(installedExe()); err == nil {
		_ = startAgent(installedExe())
	}
}

func platformAlert(title, msg string) {
	taskDialog(&taskDialogOpts{Title: "Flick", Instruction: title, Content: msg, CommonButtons: TDCBF_OK_BUTTON, Icon: TD_INFORMATION_ICON})
}
