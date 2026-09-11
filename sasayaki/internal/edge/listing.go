package edge

import (
	"html"
	"io/fs"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strconv"
	"strings"
)

// The directory index, for the sites that ask for one.
//
// Off unless the customer switched it on, and that default is the important part: a
// listing turns a directory of build output into a file browser, and a site that
// accidentally shipped a .env or a database dump has just published it. spec.SiteOptions
// carries the flag rather than this package deciding, so the answer is the customer's and
// is visible in the panel.
//
// One column, large touch targets, no JavaScript. Most of the people who will ever look
// at one of these are on a phone, and a four-column table with a hover state is not
// something they can use.

const listingTemplate = `<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>%TITLE%</title>
<style>
:root{color-scheme:light dark}
body{margin:0;padding:1.25rem;font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
h1{font-size:1.05rem;font-weight:600;margin:0 0 1rem;word-break:break-all;opacity:.7}
ul{list-style:none;margin:0;padding:0;max-width:44rem}
li{border-bottom:1px solid color-mix(in srgb,currentColor 12%,transparent)}
a{display:flex;justify-content:space-between;gap:1rem;align-items:baseline;
padding:.85rem .25rem;text-decoration:none;color:inherit}
a:hover,a:focus{background:color-mix(in srgb,currentColor 7%,transparent)}
span.name{word-break:break-all}
span.size{font-variant-numeric:tabular-nums;opacity:.5;font-size:.85rem;white-space:nowrap}
small{display:block;margin-top:2rem;opacity:.45;font-size:.8rem}
</style>
</head><body>
<h1>%TITLE%</h1>
<ul>%ROWS%</ul>
<small>wisper</small>
</body></html>
`

// writeListing renders one directory.
func writeListing(w http.ResponseWriter, r *http.Request, root *os.Root, name string) {
	open, err := root.Open(name)
	if err != nil {
		writePage(w, http.StatusNotFound, "Not found",
			"This directory is in the release but could not be opened.")
		return
	}
	defer open.Close()

	children, err := open.ReadDir(-1)
	if err != nil {
		writePage(w, http.StatusInternalServerError, "This directory could not be read",
			"The release is on the node but the contents of this directory could not be listed.")
		return
	}

	// Directories first, then names, both case-insensitively. A build's output is usually
	// a handful of directories and a hundred hashed asset names, and putting the
	// directories where a person will look for them is the whole of the ordering problem.
	sort.Slice(children, func(i, j int) bool {
		if children[i].IsDir() != children[j].IsDir() {
			return children[i].IsDir()
		}
		return strings.ToLower(children[i].Name()) < strings.ToLower(children[j].Name())
	})

	var rows strings.Builder
	if r.URL.Path != "/" {
		rows.WriteString(`<li><a href="../"><span class="name">../</span></a></li>`)
	}
	for _, child := range children {
		rows.WriteString(listingRow(child))
	}

	body := listingTemplate
	body = strings.ReplaceAll(body, "%TITLE%", html.EscapeString(r.URL.Path))
	body = strings.ReplaceAll(body, "%ROWS%", rows.String())

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Content-Length", strconv.Itoa(len(body)))
	// A listing describes a release directory that a deployment can replace at any
	// moment, and a cached one would point at files that are no longer there.
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	if r.Method != http.MethodHead {
		_, _ = w.Write([]byte(body))
	}
}

// listingRow is one line of the index.
func listingRow(child fs.DirEntry) string {
	name := child.Name()
	label := name
	size := ""

	if child.IsDir() {
		label += "/"
	} else if info, err := child.Info(); err == nil {
		size = formatBytes(info.Size())
	}

	// url.PathEscape on the segment, not on the whole path: a file called "a b&c.txt" has
	// to become a link that reaches the file called "a b&c.txt" and not three of them.
	link := url.PathEscape(name)
	if child.IsDir() {
		link += "/"
	}

	return `<li><a href="` + html.EscapeString(link) + `">` +
		`<span class="name">` + html.EscapeString(label) + `</span>` +
		`<span class="size">` + html.EscapeString(size) + `</span></a></li>`
}

// formatBytes is a size a person reads rather than counts.
func formatBytes(size int64) string {
	const unit = 1024
	if size < unit {
		return strconv.FormatInt(size, 10) + " B"
	}
	value := float64(size)
	for _, suffix := range []string{"kB", "MB", "GB", "TB"} {
		value /= unit
		if value < unit {
			return strconv.FormatFloat(value, 'f', 1, 64) + " " + suffix
		}
	}
	return strconv.FormatFloat(value, 'f', 1, 64) + " TB"
}
