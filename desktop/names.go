package main

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode"
)

var winReserved = regexp.MustCompile(`(?i)^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$`)

// safeName reduces whatever the phone sent to a bare, safe file name, so a
// sender can never write outside the save folder.
func safeName(raw string) string {
	name := strings.ToValidUTF8(raw, "_")
	if i := strings.LastIndexAny(name, `/\`); i >= 0 {
		name = name[i+1:]
	}
	name = strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f || strings.ContainsRune(`<>:"|?*`, r) {
			return '_'
		}
		return r
	}, name)
	trim := func(r rune) bool { return r == '.' || unicode.IsSpace(r) }
	name = strings.TrimLeftFunc(name, trim)
	name = strings.TrimRightFunc(name, trim)
	if winReserved.MatchString(name) {
		name = "_" + name
	}
	if name == "" {
		name = "file"
	}
	if len(name) > 200 {
		ext := filepath.Ext(name)
		if len(ext) > 16 {
			ext = ext[:16]
		}
		base := strings.TrimSuffix(name, filepath.Ext(name))
		for len(base)+len(ext) > 200 {
			_, size := lastRune(base)
			base = base[:len(base)-size]
		}
		name = base + ext
	}
	return name
}

func lastRune(s string) (rune, int) {
	for i := len(s) - 1; i >= 0; i-- {
		if s[i]&0xC0 != 0x80 {
			return rune(s[i]), len(s) - i
		}
	}
	return 0, 1
}

// reserveUnique atomically claims "name.ext", "name (1).ext", … in dir.
func reserveUnique(dir, name string) (string, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	ext := filepath.Ext(name)
	base := strings.TrimSuffix(name, ext)
	for i := 0; i < 10000; i++ {
		candidate := name
		if i > 0 {
			candidate = fmt.Sprintf("%s (%d)%s", base, i, ext)
		}
		p := filepath.Join(dir, candidate)
		f, err := os.OpenFile(p, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
		if err == nil {
			f.Close()
			return p, nil
		}
		if !errors.Is(err, os.ErrExist) {
			return "", err
		}
	}
	return "", errors.New("no free file name")
}

func humanSize(b int64) string {
	if b < 0 {
		return ""
	}
	units := []string{"B", "KB", "MB", "GB", "TB"}
	v := float64(b)
	i := 0
	for v >= 1000 && i < len(units)-1 {
		v /= 1000
		i++
	}
	if v >= 100 || i == 0 {
		return fmt.Sprintf("%d %s", int64(v+0.5), units[i])
	}
	return fmt.Sprintf("%.1f %s", v, units[i])
}

func kindOf(mime string) string {
	m := strings.ToLower(mime)
	switch {
	case strings.HasPrefix(m, "image/"):
		return "photo"
	case strings.HasPrefix(m, "video/"):
		return "video"
	case strings.HasPrefix(m, "audio/"):
		return "audio file"
	case m == "application/pdf":
		return "PDF"
	}
	return "file"
}

// describeItems: "a photo", "3 photos", "2 videos", "5 items"
func describeItems(items []Item) string {
	if len(items) == 0 {
		return ""
	}
	kinds := map[string]bool{}
	var k string
	for _, it := range items {
		k = kindOf(it.Mime)
		kinds[k] = true
	}
	n := len(items)
	if len(kinds) == 1 {
		if n == 1 {
			if strings.ContainsRune("aeiou", rune(k[0])) {
				return "an " + k
			}
			return "a " + k
		}
		return fmt.Sprintf("%d %ss", n, k)
	}
	return fmt.Sprintf("%d items", n)
}

// namesLine: "18.0 MB · IMG_1.jpg, IMG_2.jpg +1"
func namesLine(a *Ask) string {
	var names []string
	for i, it := range a.Items {
		if i == 2 {
			break
		}
		names = append(names, it.Name)
	}
	line := strings.Join(names, ", ")
	if len(a.Items) > 2 {
		line += fmt.Sprintf(" +%d", len(a.Items)-2)
	}
	if a.TotalSize > 0 {
		return humanSize(a.TotalSize) + " · " + line
	}
	return line
}

func capitalize(s string) string {
	if s == "" {
		return s
	}
	return strings.ToUpper(s[:1]) + s[1:]
}

func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n-1]) + "…"
}

func isHTTPURL(s string) bool {
	t := strings.TrimSpace(s)
	if strings.ContainsAny(t, " \t\r\n") {
		return false
	}
	l := strings.ToLower(t)
	return (strings.HasPrefix(l, "http://") || strings.HasPrefix(l, "https://")) && len(t) > 8
}

func prettyDir(dir string) string {
	home, _ := os.UserHomeDir()
	if home != "" && strings.HasPrefix(dir, home+string(os.PathSeparator)) {
		return strings.TrimPrefix(dir, home+string(os.PathSeparator))
	}
	return dir
}
