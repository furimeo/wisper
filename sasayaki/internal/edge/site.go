package edge

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Serving a static site: a directory, and no process at all (design section 5.5).
//
// Two properties make this worth writing rather than delegating. The first is that the
// release directory is opened per request through os.Root, so every path is resolved
// inside it by the kernel - a symlink a customer's build dropped into their output cannot
// name /etc/passwd, and neither can a request path, and neither of those is a check this
// code has to remember to perform. The second is that opening per request is what makes
// publishing atomic: the builder swaps the `current` symlink and the very next visitor
// follows it, with no reload and no window where the directory is half-replaced.

// site serves one workload's published release.
type site struct {
	// directory is <state>/sites/<workload>/current - the symlink, not the release it
	// points at. Resolved on every request, which is the whole point.
	directory string
	options   spec.SiteOptions
}

func newSite(directory string, options spec.SiteOptions) *site {
	return &site{directory: directory, options: options}
}

// ready reports whether there is anything published to serve.
//
// A site with no release is the normal state of a service between being created and its
// first successful deployment, so the message is written for the customer looking at the
// panel wondering why their domain shows nothing.
func (s *site) ready() error {
	info, err := os.Stat(s.directory)
	switch {
	case errors.Is(err, fs.ErrNotExist):
		return errors.New("no release has been published for this site yet")
	case err != nil:
		return fmt.Errorf("the published release of this site cannot be read: %w", err)
	case !info.IsDir():
		return errors.New("the published release of this site is not a directory")
	}
	return nil
}

func (s *site) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		writePage(w, http.StatusMethodNotAllowed, "This address only serves files",
			fmt.Sprintf("A static site has nothing to do with a %s request. If this should "+
				"have reached an application, the domain is pointed at the wrong service.",
				r.Method))
		return
	}

	root, err := os.OpenRoot(s.directory)
	if err != nil {
		writePage(w, http.StatusServiceUnavailable, "This site has not been published yet",
			"There is no release on this node for this address. Deploy the site and it will "+
				"appear here without anything else having to be restarted.")
		return
	}
	defer root.Close()

	name := requestedFile(r.URL.Path)
	info, err := root.Stat(name)
	switch {
	case err != nil:
		s.missing(w, r, root)
		return
	case info.IsDir():
		s.directoryAt(w, r, root, name)
		return
	}
	s.file(w, r, root, name, http.StatusOK)
}

// directoryAt serves a directory: its index file, a listing, or nothing.
func (s *site) directoryAt(w http.ResponseWriter, r *http.Request, root *os.Root, name string) {
	// Without the trailing slash every relative link on the page below resolves one level
	// too high, so the browser is sent to the canonical form first.
	if !strings.HasSuffix(r.URL.Path, "/") {
		redirect(w, r, r.URL.Path+"/")
		return
	}

	index := joinName(name, s.options.Index())
	if info, err := root.Stat(index); err == nil && !info.IsDir() {
		s.file(w, r, root, index, http.StatusOK)
		return
	}

	if s.options.DirectoryListing {
		writeListing(w, r, root, name)
		return
	}

	// A directory with no index and no listing is not an error in the release; it is a
	// path that does not exist as far as a visitor is concerned, and it takes the same
	// route as any other miss so that a single-page application still answers.
	s.missing(w, r, root)
}

// missing is what happens when the requested path is not in the release.
//
// Three behaviours, in the order the customer chose them. A single-page application wants
// its index served with a 200, because the path is a route its own router will handle. A
// site with a custom error page wants that page with a 404. Everything else gets the
// plain one, which still says something a person can act on.
func (s *site) missing(w http.ResponseWriter, r *http.Request, root *os.Root) {
	if s.options.SPAFallback {
		index := s.options.Index()
		if info, err := root.Stat(index); err == nil && !info.IsDir() {
			s.file(w, r, root, index, http.StatusOK)
			return
		}
	}

	if s.options.NotFoundFile != "" {
		page := requestedFile("/" + s.options.NotFoundFile)
		if info, err := root.Stat(page); err == nil && !info.IsDir() {
			s.file(w, r, root, page, http.StatusNotFound)
			return
		}
	}

	writePage(w, http.StatusNotFound, "Not found",
		"This address is served by wisper, but there is no such file in the site's current "+
			"release.")
}

// file writes one file out of the release.
//
// Status is a parameter because a 404 page is an ordinary file served with an
// extraordinary code, and http.ServeContent only ever writes 200 - so for anything else
// the conditional-request and range handling it does is deliberately given up. That is
// the right trade: a Range request for an error page is not a thing worth supporting, and
// a 404 that a browser cached because it looked like a 200 is a bug that outlives the
// deployment that caused it.
func (s *site) file(w http.ResponseWriter, r *http.Request, root *os.Root, name string, status int) {
	open, err := root.Open(name)
	if err != nil {
		writePage(w, http.StatusNotFound, "Not found",
			"This address is served by wisper, but the file behind it could not be opened.")
		return
	}
	defer open.Close()

	info, err := open.Stat()
	if err != nil {
		writePage(w, http.StatusInternalServerError, "This file could not be read",
			"The release directory is on the node but this file in it could not be read.")
		return
	}

	// A document is revalidated, an asset is not touched. Without this a browser is free
	// to invent a freshness lifetime for a response with only a Last-Modified header, and
	// a customer who has just deployed would be looking at yesterday's page with no way
	// to know why. Assets are left alone because a build that fingerprints its filenames
	// wants them cached, and one that does not gets the same revalidation as the document
	// that links them.
	if isDocument(name) {
		w.Header().Set("Cache-Control", "no-cache")
	}

	if status == http.StatusOK {
		http.ServeContent(w, r, path.Base(name), info.ModTime(), open)
		return
	}

	w.Header().Set("Content-Type", contentType(name))
	w.WriteHeader(status)
	if r.Method != http.MethodHead {
		_, _ = io.Copy(w, open)
	}
}

// requestedFile turns a URL path into a name inside the release directory.
//
// path.Clean resolves the dot segments, and the leading slash it is given guarantees the
// result cannot start with "..". os.Root would refuse an escape anyway; this is here so
// that the name handed to it is the one a person reading a log would expect.
func requestedFile(urlPath string) string {
	cleaned := path.Clean("/" + strings.TrimPrefix(urlPath, "/"))
	cleaned = strings.TrimPrefix(cleaned, "/")
	if cleaned == "" || cleaned == "." {
		return "."
	}
	return filepath.FromSlash(cleaned)
}

// joinName is requestedFile's join: it keeps "." meaning the release root.
func joinName(directory, name string) string {
	if directory == "." {
		return filepath.FromSlash(name)
	}
	return filepath.Join(directory, filepath.FromSlash(name))
}

// isDocument reports whether a file is the kind a person navigates to rather than the
// kind a page pulls in.
func isDocument(name string) bool {
	switch strings.ToLower(path.Ext(filepath.ToSlash(name))) {
	case ".html", ".htm", ".xhtml", "":
		return true
	default:
		return false
	}
}
