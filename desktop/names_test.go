package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSafeName(t *testing.T) {
	cases := map[string]string{
		"../../etc/passwd":        "passwd",
		`..\..\Windows\win.ini`:   "win.ini",
		"CON.txt":                 "_CON.txt",
		"  .hidden ":              "hidden",
		"a<b>c:d\"e|f?g*h.jpg":    "a_b_c_d_e_f_g_h.jpg",
		"":                        "file",
		"...":                     "file",
		"photo.jpg.":              "photo.jpg",
		"line\nbreak.txt":         "line_break.txt",
		"IMG_20261012_094112.jpg": "IMG_20261012_094112.jpg",
		"résumé – final.pdf":      "résumé – final.pdf",
	}
	for in, want := range cases {
		if got := safeName(in); got != want {
			t.Errorf("safeName(%q) = %q, want %q", in, got, want)
		}
	}
	long := strings.Repeat("é", 300) + ".jpeg"
	got := safeName(long)
	if len(got) > 200 || !strings.HasSuffix(got, ".jpeg") || !strings.HasPrefix(got, "é") {
		t.Errorf("long name not trimmed correctly: %d bytes, %q…", len(got), got[:10])
	}
}

func TestReserveUnique(t *testing.T) {
	dir := t.TempDir()
	a, _ := reserveUnique(dir, "x.txt")
	b, _ := reserveUnique(dir, "x.txt")
	c, _ := reserveUnique(dir, "x.txt")
	if filepath.Base(a) != "x.txt" || filepath.Base(b) != "x (1).txt" || filepath.Base(c) != "x (2).txt" {
		t.Fatal(a, b, c)
	}
	if _, err := os.Stat(c); err != nil {
		t.Fatal(err)
	}
}

func TestDescribe(t *testing.T) {
	img := Item{Mime: "image/jpeg"}
	vid := Item{Mime: "video/mp4"}
	cases := []struct {
		items []Item
		want  string
	}{
		{[]Item{img}, "a photo"},
		{[]Item{img, img, img}, "3 photos"},
		{[]Item{vid}, "a video"},
		{[]Item{{Mime: "audio/mpeg"}}, "an audio file"},
		{[]Item{{Mime: "application/pdf"}, {Mime: "application/pdf"}}, "2 PDFs"},
		{[]Item{img, vid}, "2 items"},
	}
	for _, c := range cases {
		if got := describeItems(c.items); got != c.want {
			t.Errorf("describeItems = %q, want %q", got, c.want)
		}
	}
	if humanSize(18_000_000) != "18.0 MB" || humanSize(512) != "512 B" || humanSize(150_000_000) != "150 MB" {
		t.Error(humanSize(18_000_000), humanSize(512), humanSize(150_000_000))
	}
	if !isHTTPURL("https://example.com/a") || isHTTPURL("javascript:alert(1)") || isHTTPURL("see https://x.com") {
		t.Error("isHTTPURL")
	}
}
