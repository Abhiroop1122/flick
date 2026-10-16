//go:build windows

package main

// Minimal Win32 bindings for Flick's Windows UI: tray icon, Task Dialogs
// (native accept/progress dialogs), clipboard and folder picker.
// Pure Go (no cgo), so the binary stays small and cross-compiles.

import (
	"encoding/binary"
	"path/filepath"
	"runtime"
	"sync"
	"syscall"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	user32   = windows.NewLazySystemDLL("user32.dll")
	shell32  = windows.NewLazySystemDLL("shell32.dll")
	kernel32 = windows.NewLazySystemDLL("kernel32.dll")
	ole32    = windows.NewLazySystemDLL("ole32.dll")
	comdlg32 = windows.NewLazySystemDLL("comdlg32.dll")
	// comctl32 is loaded by plain name so the manifest's side-by-side
	// redirection to Common Controls v6 (which has TaskDialogIndirect) applies.
	comctl32 = syscall.NewLazyDLL("comctl32.dll")

	pRegisterClassExW         = user32.NewProc("RegisterClassExW")
	pCreateWindowExW          = user32.NewProc("CreateWindowExW")
	pDefWindowProcW           = user32.NewProc("DefWindowProcW")
	pGetMessageW              = user32.NewProc("GetMessageW")
	pTranslateMessage         = user32.NewProc("TranslateMessage")
	pDispatchMessageW         = user32.NewProc("DispatchMessageW")
	pPostMessageW             = user32.NewProc("PostMessageW")
	pSendMessageW             = user32.NewProc("SendMessageW")
	pPostQuitMessage          = user32.NewProc("PostQuitMessage")
	pCreatePopupMenu          = user32.NewProc("CreatePopupMenu")
	pAppendMenuW              = user32.NewProc("AppendMenuW")
	pTrackPopupMenu           = user32.NewProc("TrackPopupMenu")
	pDestroyMenu              = user32.NewProc("DestroyMenu")
	pSetForegroundWindow      = user32.NewProc("SetForegroundWindow")
	pGetCursorPos             = user32.NewProc("GetCursorPos")
	pLoadImageW               = user32.NewProc("LoadImageW")
	pRegisterWindowMessageW   = user32.NewProc("RegisterWindowMessageW")
	pSetWindowPos             = user32.NewProc("SetWindowPos")
	pGetWindowRect            = user32.NewProc("GetWindowRect")
	pSystemParametersInfoW    = user32.NewProc("SystemParametersInfoW")
	pMessageBeep              = user32.NewProc("MessageBeep")
	pMessageBoxW              = user32.NewProc("MessageBoxW")
	pOpenClipboard            = user32.NewProc("OpenClipboard")
	pEmptyClipboard           = user32.NewProc("EmptyClipboard")
	pSetClipboardData         = user32.NewProc("SetClipboardData")
	pCloseClipboard           = user32.NewProc("CloseClipboard")
	pGetSystemMetrics         = user32.NewProc("GetSystemMetrics")
	pIsClipboardFormatAvail   = user32.NewProc("IsClipboardFormatAvailable")
	pGetClipboardData         = user32.NewProc("GetClipboardData")
	pRegisterClipboardFormatW = user32.NewProc("RegisterClipboardFormatW")
	pAddClipboardFormatListen = user32.NewProc("AddClipboardFormatListener")
	pGetOpenFileNameW         = comdlg32.NewProc("GetOpenFileNameW")
	pGlobalSize               = kernel32.NewProc("GlobalSize")
	pShellNotifyIconW         = shell32.NewProc("Shell_NotifyIconW")
	pShellExecuteExW          = shell32.NewProc("ShellExecuteExW")
	pSHBrowseForFolderW       = shell32.NewProc("SHBrowseForFolderW")
	pSHGetPathFromIDListW     = shell32.NewProc("SHGetPathFromIDListW")
	pGetModuleHandleW         = kernel32.NewProc("GetModuleHandleW")
	pGlobalAlloc              = kernel32.NewProc("GlobalAlloc")
	pGlobalLock               = kernel32.NewProc("GlobalLock")
	pGlobalUnlock             = kernel32.NewProc("GlobalUnlock")
	pRtlMoveMemory            = kernel32.NewProc("RtlMoveMemory")
	pCoInitializeEx           = ole32.NewProc("CoInitializeEx")
	pCoTaskMemFree            = ole32.NewProc("CoTaskMemFree")
	pTaskDialogIndirect       = comctl32.NewProc("TaskDialogIndirect")
	pLoadIconMetric           = comctl32.NewProc("LoadIconMetric")
)

const (
	WM_NULL            = 0x0000
	WM_DESTROY         = 0x0002
	WM_CLOSE           = 0x0010
	WM_CONTEXTMENU     = 0x007B
	WM_CLIPBOARDUPDATE = 0x031D
	WM_LBUTTONUP       = 0x0202
	WM_USER            = 0x0400
	WM_APP             = 0x8000

	NIM_ADD        = 0
	NIM_MODIFY     = 1
	NIM_DELETE     = 2
	NIM_SETVERSION = 4

	NIF_MESSAGE = 0x01
	NIF_ICON    = 0x02
	NIF_TIP     = 0x04
	NIF_INFO    = 0x10
	NIF_SHOWTIP = 0x80

	NIIF_USER       = 0x04
	NIIF_LARGE_ICON = 0x20

	NOTIFYICON_VERSION_4 = 4
	NIN_SELECT           = WM_USER + 0
	NIN_KEYSELECT        = WM_USER + 1
	NIN_BALLOONUSERCLICK = WM_USER + 5

	MF_STRING    = 0x0000
	MF_GRAYED    = 0x0001
	MF_DISABLED  = 0x0002
	MF_CHECKED   = 0x0008
	MF_SEPARATOR = 0x0800

	TPM_RIGHTBUTTON = 0x0002
	TPM_NONOTIFY    = 0x0080
	TPM_RETURNCMD   = 0x0100

	IMAGE_ICON  = 1
	SM_CXICON   = 11
	SM_CXSMICON = 49

	SWP_NOSIZE      = 0x0001
	SWP_NOACTIVATE  = 0x0010
	SWP_SHOWWINDOW  = 0x0040
	HWND_TOPMOST    = ^uintptr(0) // (HWND)-1
	SPI_GETWORKAREA = 0x0030
	MB_ICONASTERISK = 0x40
	MB_YESNO        = 0x04
	MB_OK           = 0x00
	MB_ICONQUESTION = 0x20
	MB_TOPMOST      = 0x40000
	IDYES           = 6

	IDOK     = 1
	IDCANCEL = 2

	// Task dialog
	TDF_USE_HICON_MAIN            = 0x0002
	TDF_ALLOW_DIALOG_CANCELLATION = 0x0008
	TDF_USE_COMMAND_LINKS         = 0x0010
	TDF_SHOW_PROGRESS_BAR         = 0x0200
	TDF_SHOW_MARQUEE_PROGRESS_BAR = 0x0400
	TDF_CALLBACK_TIMER            = 0x0800
	TDF_CAN_BE_MINIMIZED          = 0x8000
	TDF_NO_SET_FOREGROUND         = 0x10000

	TDCBF_OK_BUTTON     = 0x01
	TDCBF_CANCEL_BUTTON = 0x08
	TDCBF_CLOSE_BUTTON  = 0x20

	TDN_CREATED        = 0
	TDN_BUTTON_CLICKED = 2
	TDN_TIMER          = 4
	TDN_DESTROYED      = 5

	TDM_CLICK_BUTTON             = WM_USER + 102
	TDM_SET_PROGRESS_BAR_RANGE   = WM_USER + 105
	TDM_SET_PROGRESS_BAR_POS     = WM_USER + 106
	TDM_SET_ELEMENT_TEXT         = WM_USER + 108
	TDM_SET_MARQUEE_PROGRESS_BAR = WM_USER + 103
	TDM_SET_PROGRESS_BAR_MARQUEE = WM_USER + 107
	TDE_CONTENT                  = 0
	TDE_MAIN_INSTRUCTION         = 3

	TD_ERROR_ICON       = 0xFFFE
	TD_INFORMATION_ICON = 0xFFFD

	CF_UNICODETEXT = 13
	GMEM_MOVEABLE  = 0x0002

	SEE_MASK_NOCLOSEPROCESS = 0x00000040
	SEE_MASK_NO_CONSOLE     = 0x00008000
	SW_HIDE                 = 0
	SW_SHOWNORMAL           = 1
)

type point struct{ X, Y int32 }
type rect struct{ Left, Top, Right, Bottom int32 }

type msg struct {
	Hwnd     uintptr
	Message  uint32
	WParam   uintptr
	LParam   uintptr
	Time     uint32
	Pt       point
	LPrivate uint32
}

type wndClassEx struct {
	Size       uint32
	Style      uint32
	WndProc    uintptr
	ClsExtra   int32
	WndExtra   int32
	Instance   uintptr
	Icon       uintptr
	Cursor     uintptr
	Background uintptr
	MenuName   *uint16
	ClassName  *uint16
	IconSm     uintptr
}

type notifyIconData struct {
	CbSize           uint32
	HWnd             uintptr
	UID              uint32
	UFlags           uint32
	UCallbackMessage uint32
	HIcon            uintptr
	SzTip            [128]uint16
	DwState          uint32
	DwStateMask      uint32
	SzInfo           [256]uint16
	UVersion         uint32
	SzInfoTitle      [64]uint16
	DwInfoFlags      uint32
	GuidItem         windows.GUID
	HBalloonIcon     uintptr
}

type shellExecuteInfo struct {
	Size       uint32
	Mask       uint32
	Hwnd       uintptr
	Verb       *uint16
	File       *uint16
	Parameters *uint16
	Directory  *uint16
	Show       int32
	InstApp    uintptr
	IDList     uintptr
	Class      *uint16
	KeyClass   uintptr
	HotKey     uint32
	Icon       uintptr
	Process    windows.Handle
}

type browseInfo struct {
	Owner       uintptr
	Root        uintptr
	DisplayName *uint16
	Title       *uint16
	Flags       uint32
	Callback    uintptr
	LParam      uintptr
	Image       int32
}

func u16(s string) *uint16 {
	p, err := windows.UTF16PtrFromString(s)
	if err != nil {
		p, _ = windows.UTF16PtrFromString("")
	}
	return p
}

func copyU16(dst []uint16, s string) {
	src, _ := windows.UTF16FromString(s)
	if len(src) > len(dst) {
		src = src[:len(dst)]
		src[len(src)-1] = 0
	}
	copy(dst, src)
}

func hInstance() uintptr {
	h, _, _ := pGetModuleHandleW.Call(0)
	return h
}

// loadAppIcon loads the "APP" icon from Flick.exe's resources at the given metric (0 small, 1 large).
func loadAppIcon(large bool) uintptr {
	name := u16("APP")
	var h uintptr
	lim := uintptr(0)
	if large {
		lim = 1
	}
	if pLoadIconMetric.Find() == nil {
		if r, _, _ := pLoadIconMetric.Call(hInstance(), uintptr(unsafe.Pointer(name)), lim, uintptr(unsafe.Pointer(&h))); r == 0 && h != 0 {
			return h
		}
	}
	metric := uintptr(SM_CXSMICON)
	if large {
		metric = SM_CXICON
	}
	size, _, _ := pGetSystemMetrics.Call(metric)
	h, _, _ = pLoadImageW.Call(hInstance(), uintptr(unsafe.Pointer(name)), IMAGE_ICON, size, size, 0)
	return h
}

func messageBox(text, title string, flags uintptr) int {
	r, _, _ := pMessageBoxW.Call(0, uintptr(unsafe.Pointer(u16(text))), uintptr(unsafe.Pointer(u16(title))), flags|MB_TOPMOST)
	return int(r)
}

// ---------------------------------------------------------------- Task Dialog

type tdButton struct {
	ID   int32
	Text string
}

type taskDialogOpts struct {
	Title, Instruction, Content, Verification string
	Buttons                                   []tdButton
	DefaultButton                             int32
	CommonButtons                             uint32
	Flags                                     uint32
	Icon                                      uintptr // HICON (with TDF_USE_HICON_MAIN) or a TD_*_ICON id
	Corner                                    bool    // place in the bottom-right corner, always on top
	OnCreated                                 func(hwnd uintptr)
	OnTimer                                   func(hwnd uintptr)
	OnDestroyed                               func()
}

var (
	tdMu       sync.Mutex
	tdNext     uintptr
	tdRegistry = map[uintptr]*taskDialogOpts{}
	tdCallback = syscall.NewCallback(taskDialogProc)
	cornerMu   sync.Mutex
	cornerOpen []uintptr
)

func taskDialogProc(hwnd, msg, wParam, lParam, ref uintptr) uintptr {
	tdMu.Lock()
	o := tdRegistry[ref]
	tdMu.Unlock()
	if o == nil {
		return 0
	}
	switch msg {
	case TDN_CREATED:
		if o.Corner {
			placeInCorner(hwnd)
		}
		if o.OnCreated != nil {
			o.OnCreated(hwnd)
		}
	case TDN_TIMER:
		if o.OnTimer != nil {
			o.OnTimer(hwnd)
		}
	case TDN_DESTROYED:
		if o.Corner {
			cornerMu.Lock()
			for i, h := range cornerOpen {
				if h == hwnd {
					cornerOpen = append(cornerOpen[:i], cornerOpen[i+1:]...)
					break
				}
			}
			cornerMu.Unlock()
		}
		if o.OnDestroyed != nil {
			o.OnDestroyed()
		}
	}
	return 0
}

// placeInCorner puts a dialog in the bottom-right of the screen (stacking
// several), always on top but without stealing keyboard focus — like AirDrop.
func placeInCorner(hwnd uintptr) {
	var wr, wa rect
	pGetWindowRect.Call(hwnd, uintptr(unsafe.Pointer(&wr)))
	pSystemParametersInfoW.Call(SPI_GETWORKAREA, 0, uintptr(unsafe.Pointer(&wa)), 0)
	w, h := wr.Right-wr.Left, wr.Bottom-wr.Top
	cornerMu.Lock()
	y := wa.Bottom - 12
	for _, other := range cornerOpen {
		var r rect
		pGetWindowRect.Call(other, uintptr(unsafe.Pointer(&r)))
		y -= (r.Bottom - r.Top) + 8
	}
	cornerOpen = append(cornerOpen, hwnd)
	cornerMu.Unlock()
	y -= h
	if y < wa.Top {
		y = wa.Top
	}
	x := wa.Right - w - 12
	pSetWindowPos.Call(hwnd, HWND_TOPMOST, uintptr(x), uintptr(y), 0, 0, SWP_NOSIZE|SWP_NOACTIVATE|SWP_SHOWWINDOW)
}

func sendMsg(hwnd uintptr, m uint32, w, l uintptr) uintptr {
	r, _, _ := pSendMessageW.Call(hwnd, uintptr(m), w, l)
	return r
}

func tdSetText(hwnd uintptr, element uintptr, text string) {
	p := u16(text)
	sendMsg(hwnd, TDM_SET_ELEMENT_TEXT, element, uintptr(unsafe.Pointer(p)))
	runtime.KeepAlive(p)
}

func tdClick(hwnd uintptr, id int32) { sendMsg(hwnd, TDM_CLICK_BUTTON, uintptr(id), 0) }

// taskDialog shows a native Windows task dialog and returns the clicked
// button id and whether the verification checkbox was ticked. It runs on its
// own OS thread, so several can be open at once.
func taskDialog(o *taskDialogOpts) (int32, bool) {
	type result struct {
		btn int32
		ver bool
	}
	ch := make(chan result, 1)
	go func() {
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		b, v := taskDialogOnThread(o)
		ch <- result{b, v}
	}()
	r := <-ch
	return r.btn, r.ver
}

func taskDialogOnThread(o *taskDialogOpts) (int32, bool) {
	if pTaskDialogIndirect.Find() != nil { // very old comctl32: fall back to a message box
		text := o.Instruction
		if o.Content != "" {
			text += "\n\n" + o.Content
		}
		if len(o.Buttons) >= 2 {
			if messageBox(text, o.Title, MB_YESNO|MB_ICONQUESTION) == IDYES {
				return o.Buttons[0].ID, false
			}
			return o.Buttons[1].ID, false
		}
		messageBox(text, o.Title, MB_OK)
		return IDOK, false
	}

	var pin runtime.Pinner
	defer pin.Unpin()
	str := func(s string) uintptr {
		if s == "" {
			return 0
		}
		p := u16(s)
		pin.Pin(p)
		return uintptr(unsafe.Pointer(p))
	}

	// TASKDIALOG_BUTTON and TASKDIALOGCONFIG are declared with 1-byte packing
	// in commctrl.h, so they are built byte by byte (x64 layout, 160 bytes).
	var btnBuf []byte
	if len(o.Buttons) > 0 {
		btnBuf = make([]byte, 12*len(o.Buttons))
		for i, b := range o.Buttons {
			binary.LittleEndian.PutUint32(btnBuf[i*12:], uint32(b.ID))
			binary.LittleEndian.PutUint64(btnBuf[i*12+4:], uint64(str(b.Text)))
		}
		pin.Pin(&btnBuf[0])
	}
	cfg := make([]byte, 160)
	pin.Pin(&cfg[0])
	le := binary.LittleEndian
	put32 := func(off int, v uint32) { le.PutUint32(cfg[off:], v) }
	putPtr := func(off int, v uintptr) { le.PutUint64(cfg[off:], uint64(v)) }

	tdMu.Lock()
	tdNext++
	ref := tdNext
	tdRegistry[ref] = o
	tdMu.Unlock()
	defer func() { tdMu.Lock(); delete(tdRegistry, ref); tdMu.Unlock() }()

	flags := o.Flags | TDF_CALLBACK_TIMER
	put32(0, 160)                     // cbSize
	putPtr(4, 0)                      // hwndParent
	putPtr(12, hInstance())           // hInstance
	put32(20, flags)                  // dwFlags
	put32(24, o.CommonButtons)        // dwCommonButtons
	putPtr(28, str(o.Title))          // pszWindowTitle
	putPtr(36, o.Icon)                // hMainIcon / pszMainIcon
	putPtr(44, str(o.Instruction))    // pszMainInstruction
	putPtr(52, str(o.Content))        // pszContent
	put32(60, uint32(len(o.Buttons))) // cButtons
	if len(btnBuf) > 0 {
		putPtr(64, uintptr(unsafe.Pointer(&btnBuf[0]))) // pButtons
	}
	put32(72, uint32(o.DefaultButton)) // nDefaultButton
	putPtr(92, str(o.Verification))    // pszVerificationText
	putPtr(140, tdCallback)            // pfCallback
	putPtr(148, ref)                   // lpCallbackData

	var btn, radio int32
	var verified int32
	pTaskDialogIndirect.Call(uintptr(unsafe.Pointer(&cfg[0])), uintptr(unsafe.Pointer(&btn)),
		uintptr(unsafe.Pointer(&radio)), uintptr(unsafe.Pointer(&verified)))
	runtime.KeepAlive(cfg)
	runtime.KeepAlive(btnBuf)
	return btn, verified != 0
}

// ---------------------------------------------------------------- misc

func copyTextWin(text string) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	u, err := windows.UTF16FromString(text)
	if err != nil {
		return
	}
	opened := false
	for i := 0; i < 10; i++ {
		if r, _, _ := pOpenClipboard.Call(0); r != 0 {
			opened = true
			break
		}
		windows.SleepEx(50, false)
	}
	if !opened {
		return
	}
	defer pCloseClipboard.Call()
	pEmptyClipboard.Call()
	size := uintptr(len(u) * 2)
	h, _, _ := pGlobalAlloc.Call(GMEM_MOVEABLE, size)
	if h == 0 {
		return
	}
	p, _, _ := pGlobalLock.Call(h)
	if p == 0 {
		return
	}
	pRtlMoveMemory.Call(p, uintptr(unsafe.Pointer(&u[0])), size)
	pGlobalUnlock.Call(h)
	pSetClipboardData.Call(CF_UNICODETEXT, h)
}

// chooseFolder shows the standard "browse for folder" dialog.
func chooseFolder(title string) string {
	ch := make(chan string, 1)
	go func() {
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		pCoInitializeEx.Call(0, 2 /* COINIT_APARTMENTTHREADED */)
		name := make([]uint16, 260)
		bi := browseInfo{DisplayName: &name[0], Title: u16(title), Flags: 0x0001 | 0x0040 | 0x0010 /* RETURNONLYFSDIRS|NEWDIALOGSTYLE|EDITBOX */}
		pidl, _, _ := pSHBrowseForFolderW.Call(uintptr(unsafe.Pointer(&bi)))
		if pidl == 0 {
			ch <- ""
			return
		}
		buf := make([]uint16, 32768)
		pSHGetPathFromIDListW.Call(pidl, uintptr(unsafe.Pointer(&buf[0])))
		pCoTaskMemFree.Call(pidl)
		ch <- windows.UTF16ToString(buf)
	}()
	return <-ch
}

// runElevated runs a program with administrator rights (one UAC prompt) and waits for it.
func runElevated(file, params string) error {
	info := shellExecuteInfo{
		Mask:       SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NO_CONSOLE,
		Verb:       u16("runas"),
		File:       u16(file),
		Parameters: u16(params),
		Show:       SW_HIDE,
	}
	info.Size = uint32(unsafe.Sizeof(info))
	r, _, err := pShellExecuteExW.Call(uintptr(unsafe.Pointer(&info)))
	if r == 0 {
		return err
	}
	if info.Process != 0 {
		_, _ = windows.WaitForSingleObject(info.Process, 60_000)
		_ = windows.CloseHandle(info.Process)
	}
	return nil
}

// ---------------------------------------------------------------- clipboard reading

var (
	cfOnce                                         sync.Once
	cfExclude, cfViewerIgnore, cfCanUpload, cfHist uintptr
)

func registerClipFormats() {
	reg := func(n string) uintptr {
		r, _, _ := pRegisterClipboardFormatW.Call(uintptr(unsafe.Pointer(u16(n))))
		return r
	}
	cfExclude = reg("ExcludeClipboardContentFromMonitorProcessing")
	cfViewerIgnore = reg("Clipboard Viewer Ignore")
	cfCanUpload = reg("CanUploadToCloudClipboard")
	cfHist = reg("CanIncludeInClipboardHistory")
}

func clipAvailable(f uintptr) bool {
	if f == 0 {
		return false
	}
	r, _, _ := pIsClipboardFormatAvail.Call(f)
	return r != 0
}

// clipDword reads a 4-byte flag format; ok=false if absent.
func clipDword(f uintptr) (uint32, bool) {
	if !clipAvailable(f) {
		return 0, false
	}
	h, _, _ := pGetClipboardData.Call(f)
	if h == 0 {
		return 0, false
	}
	p, _, _ := pGlobalLock.Call(h)
	if p == 0 {
		return 0, false
	}
	var v uint32
	pRtlMoveMemory.Call(uintptr(unsafe.Pointer(&v)), p, 4)
	pGlobalUnlock.Call(h)
	return v, true
}

// readClipboardText returns the clipboard's text, skipping content that
// password managers mark as private. Must run on the thread owning `owner`.
func readClipboardText(owner uintptr) (string, bool) {
	cfOnce.Do(registerClipFormats)
	if !clipAvailable(CF_UNICODETEXT) {
		return "", false
	}
	opened := false
	for i := 0; i < 5; i++ {
		if r, _, _ := pOpenClipboard.Call(owner); r != 0 {
			opened = true
			break
		}
		windows.SleepEx(30, false)
	}
	if !opened {
		return "", false
	}
	defer pCloseClipboard.Call()
	if clipAvailable(cfExclude) || clipAvailable(cfViewerIgnore) {
		return "", false
	}
	if v, ok := clipDword(cfCanUpload); ok && v == 0 {
		return "", false
	}
	if v, ok := clipDword(cfHist); ok && v == 0 {
		return "", false
	}
	h, _, _ := pGetClipboardData.Call(CF_UNICODETEXT)
	if h == 0 {
		return "", false
	}
	size, _, _ := pGlobalSize.Call(h)
	if size < 2 || size > 4*maxClipText {
		return "", false
	}
	p, _, _ := pGlobalLock.Call(h)
	if p == 0 {
		return "", false
	}
	buf := make([]uint16, size/2)
	pRtlMoveMemory.Call(uintptr(unsafe.Pointer(&buf[0])), p, size/2*2)
	pGlobalUnlock.Call(h)
	return windows.UTF16ToString(buf), true
}

// ---------------------------------------------------------------- open-files dialog

type openFileName struct {
	StructSize    uint32
	Owner         uintptr
	Instance      uintptr
	Filter        *uint16
	CustomFilter  *uint16
	MaxCustFilter uint32
	FilterIndex   uint32
	File          *uint16
	MaxFile       uint32
	FileTitle     *uint16
	MaxFileTitle  uint32
	InitialDir    *uint16
	Title         *uint16
	Flags         uint32
	FileOffset    uint16
	FileExtension uint16
	DefExt        *uint16
	CustData      uintptr
	FnHook        uintptr
	TemplateName  *uint16
	PvReserved    uintptr
	DwReserved    uint32
	FlagsEx       uint32
}

// chooseFiles shows the standard Open dialog with multi-select and returns full paths.
func chooseFiles(title string) []string {
	ch := make(chan []string, 1)
	go func() {
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		pCoInitializeEx.Call(0, 2)
		buf := make([]uint16, 65536)
		filter := utf16.Encode([]rune("All files\x00*.*\x00\x00"))
		ofn := openFileName{File: &buf[0], MaxFile: uint32(len(buf)), Title: u16(title), Filter: &filter[0],
			Flags: 0x00000200 | 0x00080000 | 0x00001000 | 0x00000800 | 0x00000008} // ALLOWMULTISELECT|EXPLORER|FILEMUSTEXIST|PATHMUSTEXIST|NOCHANGEDIR
		ofn.StructSize = uint32(unsafe.Sizeof(ofn))
		if r, _, _ := pGetOpenFileNameW.Call(uintptr(unsafe.Pointer(&ofn))); r == 0 {
			ch <- nil
			return
		}
		// Either one full path, or: folder\0name1\0name2\0\0
		var parts []string
		start := 0
		for i, c := range buf {
			if c == 0 {
				if i == start {
					break
				}
				parts = append(parts, windows.UTF16ToString(buf[start:i]))
				start = i + 1
			}
		}
		if len(parts) <= 1 {
			ch <- parts
			return
		}
		var out []string
		for _, n := range parts[1:] {
			out = append(out, filepath.Join(parts[0], n))
		}
		ch <- out
	}()
	return <-ch
}
