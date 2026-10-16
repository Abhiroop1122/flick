//go:build darwin

package main

import (
	"context"
	"fmt"
	"html"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const (
	osName     = "mac"
	agentLabel = "in.piqit.flick.agent"
)

func configDir() string {
	d, err := os.UserConfigDir() // ~/Library/Application Support
	if err != nil {
		h, _ := os.UserHomeDir()
		d = filepath.Join(h, "Library", "Application Support")
	}
	return filepath.Join(d, "Flick")
}

func defaultSaveDir() string {
	h, _ := os.UserHomeDir()
	return filepath.Join(h, "Downloads")
}

func computerName() string {
	if out, err := exec.Command("/usr/sbin/scutil", "--get", "ComputerName").Output(); err == nil {
		if n := strings.TrimSpace(string(out)); n != "" {
			return n
		}
	}
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

func openURL(u string)    { _ = exec.Command("/usr/bin/open", u).Start() }
func openPath(p string)   { _ = exec.Command("/usr/bin/open", p).Start() }
func revealPath(p string) { _ = exec.Command("/usr/bin/open", "-R", p).Start() }

func copyText(text string) {
	cmd := exec.Command("/usr/bin/pbcopy")
	cmd.Env = append(os.Environ(), "LANG=en_US.UTF-8")
	cmd.Stdin = strings.NewReader(text)
	_ = cmd.Run()
}

// ---------------------------------------------------------------- AppleScript helpers
// Everything from the phone is passed as script arguments, never pasted into
// the script text, so names can't inject AppleScript.

func osascript(ctx context.Context, script string, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, "/usr/bin/osascript", append([]string{"-"}, args...)...)
	cmd.Stdin = strings.NewReader(script)
	out, err := cmd.Output()
	return strings.TrimSpace(string(out)), err
}

const dialogScript = `on run argv
	set theTitle to item 1 of argv
	set theText to item 2 of argv
	set waitSecs to (item 3 of argv) as integer
	set defBtn to item 4 of argv
	set iconList to {item 5 of argv, item 6 of argv, ""}
	set btns to items 7 thru -1 of argv
	try
		activate
	end try
	repeat with icRef in iconList
		set ic to contents of icRef
		try
			if ic is "" then
				set r to display dialog theText with title theTitle buttons btns default button defBtn with icon note giving up after waitSecs
			else
				set r to display dialog theText with title theTitle buttons btns default button defBtn with icon (POSIX file ic) giving up after waitSecs
			end if
			if gave up of r then return "__timeout__"
			return button returned of r
		on error errMsg number errNum
			if errNum is -128 then return "__cancel__"
			if ic is "" then error errMsg number errNum
		end try
	end repeat
end run`

const notifyScript = `on run argv
	display notification (item 2 of argv) with title (item 1 of argv)
end run`

const chooseScript = `on run argv
	set thePrompt to item 1 of argv
	set theItems to items 2 thru -1 of argv
	try
		activate
	end try
	set r to choose from list theItems with title "Flick" with prompt thePrompt OK button name "Choose" cancel button name "Done"
	if r is false then return ""
	return item 1 of r
end run`

const folderScript = `on run argv
	try
		activate
	end try
	try
		set f to choose folder with prompt "Save files from your phone to:" default location (POSIX file (item 1 of argv) as alias)
	on error number n
		if n is -128 then return ""
		try
			set f to choose folder with prompt "Save files from your phone to:"
		on error
			return ""
		end try
	end try
	return POSIX path of f
end run`

func bundlePath() string {
	exe, err := os.Executable()
	if err != nil {
		return ""
	}
	if r, err := filepath.EvalSymlinks(exe); err == nil {
		exe = r
	}
	if i := strings.Index(exe, ".app/Contents/MacOS/"); i >= 0 {
		return exe[:i+4]
	}
	return ""
}

func appIcon() string {
	if b := bundlePath(); b != "" {
		return filepath.Join(b, "Contents", "Resources", "Flick.icns")
	}
	return ""
}

func dialog(ctx context.Context, title, text string, wait int, icon string, buttons []string, def string) string {
	if icon == "" {
		icon = appIcon()
	}
	args := append([]string{title, text, strconv.Itoa(wait), def, icon, appIcon()}, buttons...)
	out, err := osascript(ctx, dialogScript, args...)
	if err != nil && ctx.Err() == nil {
		logf("dialog: %v", err)
	}
	return out
}

func notify(title, body string) {
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_, _ = osascript(ctx, notifyScript, title, body)
	}()
}

// ---------------------------------------------------------------- UI

type macUI struct {
	cfg *Config
	srv *Server
}

func newPlatformUI(cfg *Config) UI { return &macUI{cfg: cfg} }

func (u *macUI) Attach(s *Server) { u.srv = s }

func (u *macUI) Ask(ctx context.Context, a *Ask) Decision {
	st := u.cfg.Get()
	if st.PlaySound {
		go func() { _ = exec.Command("/usr/bin/afplay", "/System/Library/Sounds/Glass.aiff").Run() }()
	}
	who := a.Sender.Name
	if a.Pair {
		r := dialog(ctx, "Flick", fmt.Sprintf("“%s” wants to connect to this Mac.\n\n"+
			"Once connected, it can send you files without asking, you can send files to it, "+
			"and anything you copy on this Mac is ready to paste on the phone.", who),
			int(u.srv.askTimeout.Seconds())-5, "", []string{"Don't Allow", "Connect"}, "Connect")
		return Decision{Accept: r == "Connect", Trust: r == "Connect"}
	}
	var text, accept string
	if len(a.Items) == 0 {
		text = fmt.Sprintf("%s wants to send you %s:\n\n%s", who, a.Description, truncate(a.Text, 400))
		accept = "Copy"
		if isHTTPURL(a.Text) && st.OpenLinks {
			accept = "Open"
		}
	} else {
		text = fmt.Sprintf("%s wants to send you %s.\n\n%s", who, a.Description, namesLine(a))
		accept = "Accept"
	}
	thumb := ""
	if len(a.Thumb) > 0 {
		p := filepath.Join(os.TempDir(), "flick-thumb-"+a.ID+".jpg")
		if os.WriteFile(p, a.Thumb, 0o600) == nil {
			thumb = p
			defer os.Remove(p)
		}
	}
	buttons := []string{"Decline"}
	if !a.Trusted {
		buttons = append(buttons, "Always Accept")
	}
	buttons = append(buttons, accept)
	wait := int(u.srv.askTimeout.Seconds()) - 5
	switch dialog(ctx, "Flick", text, wait, thumb, buttons, accept) {
	case accept:
		return Decision{Accept: true}
	case "Always Accept":
		return Decision{Accept: true, Trust: true}
	}
	return Decision{}
}

func (u *macUI) Started(a *Ask, s *Session) {
	select {
	case <-s.Done():
		return
	case <-time.After(1500 * time.Millisecond):
	}
	p := s.Progress()
	notify("Receiving "+a.Description+"…", fmt.Sprintf("From %s · %s", a.Sender.Name, humanSize(p.Total)))
}

func (u *macUI) Finished(a *Ask, s *Session) {
	ok, reason, files := s.Result()
	st := u.cfg.Get()
	if ok {
		notify("Received "+a.Description, fmt.Sprintf("From %s · saved to %s", a.Sender.Name, filepath.Base(st.SaveDir)))
		if st.RevealWhenDone && len(files) > 0 {
			revealPath(files[0])
		}
		return
	}
	title, body := failText(a, reason)
	if len(files) > 0 {
		body += fmt.Sprintf(" %d of %d saved.", len(files), len(a.Items))
		revealPath(files[0])
	}
	notify(title, body)
}

func (u *macUI) Text(a *Ask, text string) {
	if isHTTPURL(text) && u.cfg.Get().OpenLinks {
		openURL(strings.TrimSpace(text))
		notify("Link opened", "From "+a.Sender.Name)
		return
	}
	copyText(text)
	notify("Copied to clipboard", "From "+a.Sender.Name)
}

func (u *macUI) Notice(title, body string) { notify(title, body) }

const chooseFilesScript = `on run argv
	try
		activate
	end try
	try
		set fs to choose file with prompt "Choose files to send to your phone:" with multiple selections allowed
	on error
		return ""
	end try
	set out to ""
	repeat with f in fs
		set out to out & (POSIX path of f) & linefeed
	end repeat
	return out
end run`

func onOff(b bool) string {
	if b {
		return "On"
	}
	return "Off"
}

func (u *macUI) ShowControl() {
	st := u.cfg.Get()
	phones := u.srv.Phones()
	prompt := fmt.Sprintf("Flick is on. Your phone sees this Mac as “%s”.\n%s.\nFiles are saved to %s.",
		u.cfg.DisplayName(), phonesLine(phones), prettyDir(st.SaveDir))
	send := "Send files to a phone…"
	reveal := "Show files when they arrive: " + onOff(st.RevealWhenDone)
	clip := "Share clipboard with phones: " + onOff(st.ShareClipboard)
	items := []string{send, "Open received files", "Change where files are saved…", reveal, clip}
	forget := ""
	if n := len(st.Trusted); n > 0 {
		forget = fmt.Sprintf("Forget connected phones (%d)", n)
		items = append(items, forget)
	}
	items = append(items, "Turn off Flick")
	choice, _ := osascript(context.Background(), chooseScript, append([]string{prompt}, items...)...)
	switch choice {
	case "":
	case send:
		if len(phones) == 0 {
			u.NoPhones("files")
			return
		}
		out, _ := osascript(context.Background(), chooseFilesScript)
		var paths []string
		for _, line := range strings.Split(out, "\n") {
			if line = strings.TrimSpace(line); line != "" {
				paths = append(paths, line)
			}
		}
		if len(paths) > 0 {
			u.srv.SendFiles(paths)
		}
	case "Open received files":
		openPath(st.SaveDir)
	case "Change where files are saved…":
		if dir, _ := osascript(context.Background(), folderScript, st.SaveDir); dir != "" {
			u.cfg.Update(func(s *Settings) { s.SaveDir = strings.TrimSuffix(dir, "/") })
			notify("Save folder changed", "Files from your phone will go to "+prettyDir(strings.TrimSuffix(dir, "/")))
		}
	case reveal:
		u.cfg.Update(func(s *Settings) { s.RevealWhenDone = !s.RevealWhenDone })
		u.ShowControl()
	case clip:
		u.cfg.Update(func(s *Settings) { s.ShareClipboard = !s.ShareClipboard })
		u.ShowControl()
	case forget:
		u.srv.ForgetPhones()
		notify("Phones forgotten", "Phones will need to connect again before they get files or your clipboard.")
	case "Turn off Flick":
		r := dialog(context.Background(), "Flick",
			"Turn off Flick?\n\nYour phone won't be able to send to this Mac until you open Flick again.",
			120, "", []string{"Cancel", "Turn Off"}, "Cancel")
		if r == "Turn Off" {
			notify("Flick is off", "Open Flick again to turn it back on.")
			time.Sleep(500 * time.Millisecond)
			_ = os.Remove(launchAgentPath())
			_ = exec.Command("/bin/launchctl", "bootout", fmt.Sprintf("gui/%d/%s", os.Getuid(), agentLabel)).Run()
			os.Exit(0) // if we weren't started by launchd
		}
	}
}

// ---------------------------------------------------------------- sending to a phone

func (u *macUI) PhonesChanged() {}

// ClipFromPhone: like Universal Clipboard, it just lands on the clipboard, silently.
func (u *macUI) ClipFromPhone(phone, text string) { copyText(text) }

func (u *macUI) PickPhone(phones []PhoneInfo, desc string) string {
	labels := phoneLabels(phones)
	if len(phones) == 1 {
		r := dialog(context.Background(), "Flick", fmt.Sprintf("Send %s to %s?", desc, labels[0]), 300, "",
			[]string{"Cancel", "Send"}, "Send")
		if r == "Send" {
			return phones[0].ID
		}
		return ""
	}
	choice, _ := osascript(context.Background(), chooseScript, append([]string{"Send " + desc + " to:"}, labels...)...)
	for i, l := range labels {
		if l == choice {
			return phones[i].ID
		}
	}
	return ""
}

func (u *macUI) NoPhones(desc string) {
	dialog(context.Background(), "Flick", "No phone is connected.\n\n"+
		"To connect your phone: open Flick on it (on the same Wi-Fi), find this Mac under “Your computers” and tap Connect. "+
		"After that it stays connected in the background.", 300, "", []string{"OK"}, "OK")
}

func (u *macUI) OfferStarted(o *Offer) {
	notify("Sending "+o.Description+" to "+o.PhoneName, "Waiting for "+o.PhoneName+" to accept…")
}

func (u *macUI) OfferFinished(o *Offer) {
	if title, body, _ := offerResult(o); title != "" {
		notify(title, body)
	}
}

func (u *macUI) Run() {
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT, syscall.SIGHUP)
	<-sig
	u.Quit()
	os.Exit(0) // a clean exit tells launchd not to restart us (logout / turned off)
}

func (u *macUI) Quit() { stopMDNS() }

func failText(a *Ask, reason string) (string, string) {
	who := a.Sender.Name
	switch reason {
	case "cancelled-here":
		return "Transfer cancelled", "You cancelled the transfer."
	case "cancelled":
		return "Transfer cancelled", who + " cancelled the transfer."
	case "write-failed":
		return "Couldn't save files", "Check the save folder (open Flick to change it)."
	case "timeout":
		return "Transfer stopped", who + " stopped sending."
	}
	return "Transfer failed", "The connection was lost. Keep both devices on the same Wi-Fi."
}

// ---------------------------------------------------------------- Bonjour (secondary discovery route)

var mdnsCmd *exec.Cmd
var mdnsStop = make(chan struct{})

func platformAgentStarted(s *Server) {
	go watchClipboard(s)
	go func() {
		for {
			id := s.Identity()
			cmd := exec.Command("/usr/bin/dns-sd", "-R", id.Name, "_flick._tcp", "local", strconv.Itoa(id.Port),
				"v=1", "id="+id.ID, "os=mac", "fp="+id.FP)
			if err := cmd.Start(); err != nil {
				logf("dns-sd: %v", err)
				return
			}
			mdnsCmd = cmd
			done := make(chan struct{})
			go func() { _ = cmd.Wait(); close(done) }()
			select {
			case <-mdnsStop:
				_ = cmd.Process.Kill()
				return
			case <-done:
				time.Sleep(10 * time.Second) // restart if it ever exits
			}
		}
	}()
}

func stopMDNS() {
	select {
	case <-mdnsStop:
	default:
		close(mdnsStop)
	}
	if mdnsCmd != nil && mdnsCmd.Process != nil {
		_ = mdnsCmd.Process.Kill()
	}
}

// ---------------------------------------------------------------- install / start at login

func launchAgentPath() string {
	h, _ := os.UserHomeDir()
	return filepath.Join(h, "Library", "LaunchAgents", agentLabel+".plist")
}

func launchAgentPlist(exe string) string {
	h, _ := os.UserHomeDir()
	logPath := filepath.Join(h, "Library", "Logs", "Flick.log")
	e := html.EscapeString
	return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>` + agentLabel + `</string>
	<key>ProgramArguments</key>
	<array>
		<string>` + e(exe) + `</string>
		<string>--agent</string>
	</array>
	<key>RunAtLoad</key>
	<true/>
	<key>KeepAlive</key>
	<dict>
		<key>SuccessfulExit</key>
		<false/>
	</dict>
	<key>ThrottleInterval</key>
	<integer>10</integer>
	<key>LimitLoadToSessionType</key>
	<string>Aqua</string>
	<key>ProcessType</key>
	<string>Interactive</string>
	<key>AssociatedBundleIdentifiers</key>
	<array>
		<string>in.piqit.flick</string>
	</array>
	<key>StandardOutPath</key>
	<string>` + e(logPath) + `</string>
	<key>StandardErrorPath</key>
	<string>` + e(logPath) + `</string>
</dict>
</plist>
`
}

func launchctl(args ...string) error {
	out, err := exec.Command("/bin/launchctl", args...).CombinedOutput()
	if err != nil {
		logf("launchctl %v: %v %s", args, err, strings.TrimSpace(string(out)))
	}
	return err
}

// installBundle puts Flick.app in Applications (so it survives restarts and
// isn't run from a temporary "translocated" copy) and returns the agent path.
func installBundle() (string, error) {
	src := bundlePath()
	if src == "" { // not inside an .app (development build)
		exe, err := os.Executable()
		return exe, err
	}
	home, _ := os.UserHomeDir()
	translocated := strings.Contains(src, "/AppTranslocation/")
	inApps := strings.HasPrefix(src, "/Applications/") || strings.HasPrefix(src, filepath.Join(home, "Applications")+"/")
	if inApps && !translocated {
		return filepath.Join(src, "Contents", "MacOS", "Flick"), nil
	}
	dests := []string{"/Applications/Flick.app", filepath.Join(home, "Applications", "Flick.app")}
	var lastErr error
	for _, dest := range dests {
		if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
			lastErr = err
			continue
		}
		if syscall.Access(filepath.Dir(dest), 2 /* W_OK */) != nil {
			continue
		}
		_ = os.RemoveAll(dest)
		if out, err := exec.Command("/usr/bin/ditto", "--noqtn", src, dest).CombinedOutput(); err != nil {
			lastErr = fmt.Errorf("copy to %s: %v %s", dest, err, out)
			continue
		}
		_ = exec.Command("/usr/bin/xattr", "-dr", "com.apple.quarantine", dest).Run()
		return filepath.Join(dest, "Contents", "MacOS", "Flick"), nil
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("no writable Applications folder")
	}
	return "", lastErr
}

// killOldInstances stops a running copy of the earlier (Electron) Flick.
func killOldInstances() {
	out, err := exec.Command("/bin/ps", "-axo", "pid=,args=").Output()
	if err != nil {
		return
	}
	me := os.Getpid()
	for _, line := range strings.Split(string(out), "\n") {
		f := strings.Fields(strings.TrimSpace(line))
		if len(f) < 2 {
			continue
		}
		pid, err := strconv.Atoi(f[0])
		if err != nil || pid == me {
			continue
		}
		args := strings.Join(f[1:], " ")
		if (strings.Contains(args, "Flick.app/Contents/MacOS/Flick") || strings.Contains(args, "Flick Helper")) &&
			!strings.Contains(args, "--agent") {
			_ = syscall.Kill(pid, syscall.SIGTERM)
		}
	}
}

// launchedAtLogin: an old "open at login" entry from the earlier version may
// launch the app itself at login. Then just make sure the agent runs, quietly.
func launchedAtLogin() bool {
	if out, err := exec.Command("/bin/ps", "-axo", "etime=,comm=").Output(); err == nil {
		for _, line := range strings.Split(string(out), "\n") {
			f := strings.Fields(line)
			if len(f) == 2 && strings.HasSuffix(f[1], "/loginwindow") {
				if d, ok := parseEtime(f[0]); ok {
					return d < 3*time.Minute
				}
			}
		}
	}
	return false
}

// parseEtime parses ps elapsed time: [[dd-]hh:]mm:ss
func parseEtime(s string) (time.Duration, bool) {
	days := 0
	if i := strings.Index(s, "-"); i > 0 {
		d, err := strconv.Atoi(s[:i])
		if err != nil {
			return 0, false
		}
		days, s = d, s[i+1:]
	}
	parts := strings.Split(s, ":")
	secs := 0
	for _, p := range parts {
		n, err := strconv.Atoi(p)
		if err != nil {
			return 0, false
		}
		secs = secs*60 + n
	}
	return time.Duration(days*86400+secs) * time.Second, true
}

func autostartConfigured() bool {
	_, err := os.Stat(launchAgentPath())
	return err == nil
}

func ensureAgent(dir string) {
	if _, ok := pingAgent(dir); !ok {
		_ = launchctl("kickstart", fmt.Sprintf("gui/%d/%s", os.Getuid(), agentLabel))
	}
}

func platformInstall(dir string) {
	setupLogFile(dir)
	domain := fmt.Sprintf("gui/%d", os.Getuid())
	_ = launchctl("bootout", domain+"/"+agentLabel) // stop an older agent before replacing it
	waitForAgentGone(dir, 3*time.Second)
	killOldInstances()

	exe, err := installBundle()
	if err != nil {
		logf("install: %v", err)
		dialog(context.Background(), "Flick", "Flick couldn't install itself.\n\n"+err.Error()+
			"\n\nDrag Flick into your Applications folder and open it from there.", 300, "", []string{"OK"}, "OK")
		return
	}
	plist := launchAgentPath()
	_ = os.MkdirAll(filepath.Dir(plist), 0o755)
	if err := os.WriteFile(plist, []byte(launchAgentPlist(exe)), 0o644); err != nil {
		dialog(context.Background(), "Flick", "Flick couldn't set itself to start at login.\n\n"+err.Error(), 300, "", []string{"OK"}, "OK")
		return
	}
	_ = launchctl("enable", domain+"/"+agentLabel)
	if launchctl("bootstrap", domain, plist) != nil {
		_ = launchctl("load", "-w", plist)
	}
	_ = launchctl("kickstart", domain+"/"+agentLabel)

	installQuickAction(exe)
	cfg := loadConfig(dir)
	name := cfg.DisplayName()
	if waitForAgent(dir, 10*time.Second) {
		dialog(context.Background(), "Flick",
			fmt.Sprintf("Flick is on.\n\nYour Android phone will now see this Mac as “%s”.\n\n"+
				"Flick runs quietly in the background and starts by itself every time you log in — you never need to open it again.\n\n"+
				"To send files to your phone: right-click them in Finder → Quick Actions → Send with Flick. "+
				"Text you copy on this Mac is ready to paste on your connected phone.\n\n"+
				"If your Mac asks to let Flick find devices on your local network, click Allow.\n\n"+
				"To change settings or turn it off, just open Flick again.", name),
			600, "", []string{"OK"}, "OK")
		return
	}
	dialog(context.Background(), "Flick",
		"Flick was installed but didn't start in the background.\n\nTry opening it again. If it still doesn't work, restart your Mac.",
		600, "", []string{"OK"}, "OK")
}

func platformUninstall() {
	removeQuickAction()
	_ = os.Remove(launchAgentPath())
	_ = launchctl("bootout", fmt.Sprintf("gui/%d/%s", os.Getuid(), agentLabel))
}

func platformAlert(title, msg string) {
	dialog(context.Background(), "Flick", title+"\n\n"+msg, 300, "", []string{"OK"}, "OK")
}
