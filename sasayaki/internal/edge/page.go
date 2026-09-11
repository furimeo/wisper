package edge

import (
	"html"
	"mime"
	"net/http"
	"net/url"
	"path"
	"path/filepath"
	"strconv"
	"strings"
)

// The pages the edge itself serves.
//
// Every one of them exists because the alternative is a blank frame or a dropped
// connection, and both of those tell the person looking at them nothing. A visitor who
// reaches a hostname this node does not know should learn that the address arrived
// somewhere real and is not configured; a customer whose container is down should learn
// which of the two ends is not answering. That is the whole ambition here - these are not
// branded error pages, they are sentences.
//
// The markup is deliberately tiny and inline. A node that is failing to serve a site must
// not need a stylesheet, a font or a second request to explain itself, and most of the
// people reading these are on a phone.

const pageTemplate = `<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>%TITLE%</title>
<style>
:root{color-scheme:light dark}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
padding:2rem;box-sizing:border-box;
font:16px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:34rem}
h1{margin:0 0 .5rem;font-size:1.35rem;font-weight:600}
p{margin:0;opacity:.78}
small{display:block;margin-top:2rem;opacity:.45;font-size:.8rem}
</style>
</head><body><main>
<h1>%TITLE%</h1>
<p>%DETAIL%</p>
<small>%CODE% &middot; wisper</small>
</main></body></html>
`

// writePage sends one of them.
//
// The status is written by hand rather than through http.Error so that the body is real
// HTML: a browser shown text/plain renders it in a monospace wall, which reads as a crash
// even when the message is calm.
func writePage(w http.ResponseWriter, status int, title, detail string) {
	body := pageTemplate
	body = strings.ReplaceAll(body, "%TITLE%", html.EscapeString(title))
	body = strings.ReplaceAll(body, "%DETAIL%", html.EscapeString(detail))
	body = strings.ReplaceAll(body, "%CODE%", strconv.Itoa(status))

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Content-Length", strconv.Itoa(len(body)))
	// Nothing here is worth caching: every one of these pages describes a situation that
	// is expected to be fixed within minutes.
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(body))
}

// unavailablePage is the backend for a route that is loaded and has nothing behind it.
//
// 503 rather than 404: the hostname is configured, this node is responsible for it, and
// the thing it points at is expected back. A search engine that is told 404 removes the
// page; told 503, it comes back later, which is the truthful answer while a deployment is
// in progress.
func unavailablePage(reason string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		writePage(w, http.StatusServiceUnavailable, "This address is not serving yet", reason)
	})
}

// unknownHostPage is what a request for a hostname this node does not serve gets.
//
// It is a 404 and not a redirect or a default site, because answering with somebody
// else's content for an address that merely resolves here is how one customer's domain
// starts serving another customer's site.
func unknownHostPage(w http.ResponseWriter, host string) {
	detail := "This node does not serve that hostname."
	if host != "" {
		detail = "This node does not serve " + host + "."
	}
	writePage(w, http.StatusNotFound, "Not served here",
		detail+" The address reaches wisper, so DNS is pointing at the right machine, but no "+
			"site or application here has claimed it.")
}

// redirect sends a browser somewhere else, keeping the query string.
func redirect(w http.ResponseWriter, r *http.Request, to string) {
	target := url.URL{Path: to, RawQuery: r.URL.RawQuery}
	http.Redirect(w, r, target.String(), http.StatusMovedPermanently)
}

// contentType is what to label a file the edge is writing by hand.
//
// Only used on the paths where http.ServeContent is not - an error page served with its
// own status - so the fallback matters more than the coverage: an unrecognised extension
// becomes application/octet-stream, which a browser downloads rather than executes.
func contentType(name string) string {
	if kind := mime.TypeByExtension(path.Ext(filepath.ToSlash(name))); kind != "" {
		return kind
	}
	return "application/octet-stream"
}
