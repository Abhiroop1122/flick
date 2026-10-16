// Flick for Mac and Windows: a tiny always-on receiver for files sent from
// the Flick Android app over Wi-Fi. See ../docs/PROTOCOL.md.
//
//	Flick            first open: installs itself, turns on, starts at login.
//	                 later opens: shows status and settings.
//	Flick --agent    the background receiver (started automatically).
//	Flick --send <files>  sends files to a connected phone (right-click menus).
//	Flick --uninstall  (Windows) removes Flick.
//	Flick --test     scripted receiver for automated tests.
package main

import (
	"os"
	"strings"
)

func main() {
	mode := ""
	var rest []string
	for _, a := range os.Args[1:] {
		switch a {
		case "--agent", "--uninstall", "--test", "--send":
			mode = a
		default:
			if !strings.HasPrefix(a, "-psn_") { // old macOS LaunchServices argument
				rest = append(rest, a)
			}
		}
	}
	switch mode {
	case "--send":
		runSend(rest)
	case "--agent":
		runAgent()
	case "--uninstall":
		platformUninstall()
	case "--test":
		runTest()
	default:
		runLauncher()
	}
}
