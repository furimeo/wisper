package edge

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Serving a release directory.
//
// The traversal cases are the ones worth the most: this is a directory a customer's build
// wrote, reached by a path a stranger chose, and both halves of that are hostile input.

func get(t *testing.T, h *harness, target string) *httptest.ResponseRecorder {
	t.Helper()
	recorder := httptest.NewRecorder()
	h.edge.serve(recorder, httptest.NewRequest(http.MethodGet, target, nil), false)
	return recorder
}

func siteHarness(t *testing.T, options spec.SiteOptions, files map[string]string) *harness {
	t.Helper()
	h := newHarness(t)
	h.publishSite("7", files)
	h.sync([]spec.Route{siteRoute("shop.example", "7")}, []spec.Workload{siteWorkload("7", options)})
	return h
}

func TestSiteServesTheIndexOfADirectory(t *testing.T) {
	h := siteHarness(t, spec.SiteOptions{}, map[string]string{
		"index.html":       "home",
		"docs/index.html":  "docs home",
		"assets/app.js":    "console.log(1)",
		"assets/style.css": "body{}",
	})

	for target, wanted := range map[string]string{
		"http://shop.example/":                "home",
		"http://shop.example/index.html":      "home",
		"http://shop.example/docs/":           "docs home",
		"http://shop.example/assets/app.js":   "console.log(1)",
		"http://shop.example/assets/../docs/": "docs home",
	} {
		recorder := get(t, h, target)
		if recorder.Code != http.StatusOK {
			t.Errorf("%s answered %d, want 200", target, recorder.Code)
			continue
		}
		if body := recorder.Body.String(); body != wanted {
			t.Errorf("%s served %q, want %q", target, body, wanted)
		}
	}
}

func TestSiteRedirectsADirectoryToItsTrailingSlash(t *testing.T) {
	h := siteHarness(t, spec.SiteOptions{}, map[string]string{"docs/index.html": "docs"})

	recorder := get(t, h, "http://shop.example/docs?page=2")
	if recorder.Code != http.StatusMovedPermanently {
		t.Fatalf("a directory without a trailing slash answered %d, want 301", recorder.Code)
	}
	if location := recorder.Header().Get("Location"); location != "/docs/?page=2" {
		t.Fatalf("redirected to %q, want the same path with a slash and the query kept", location)
	}
}

func TestSiteLabelsTheContentType(t *testing.T) {
	h := siteHarness(t, spec.SiteOptions{}, map[string]string{
		"index.html":    "<h1>hi</h1>",
		"assets/app.js": "console.log(1)",
	})

	recorder := get(t, h, "http://shop.example/assets/app.js")
	if kind := recorder.Header().Get("Content-Type"); !strings.Contains(kind, "javascript") {
		t.Fatalf("a .js file was labelled %q", kind)
	}
	recorder = get(t, h, "http://shop.example/")
	if kind := recorder.Header().Get("Content-Type"); !strings.Contains(kind, "text/html") {
		t.Fatalf("the index was labelled %q", kind)
	}
	if control := recorder.Header().Get("Cache-Control"); control != "no-cache" {
		t.Fatalf("a document was sent with Cache-Control %q: a browser that invents a "+
			"freshness lifetime will keep showing yesterday's deployment", control)
	}
}

func TestSiteFallsBackToTheIndexForASinglePageApplication(t *testing.T) {
	h := siteHarness(t, spec.SiteOptions{SPAFallback: true}, map[string]string{
		"index.html": "the app",
	})

	recorder := get(t, h, "http://shop.example/orders/1234")
	if recorder.Code != http.StatusOK {
		t.Fatalf("a client-side route answered %d, want the index with 200", recorder.Code)
	}
	if body := recorder.Body.String(); body != "the app" {
		t.Fatalf("served %q, want the index", body)
	}
}

func TestSiteServesACustomNotFoundPageWithA404(t *testing.T) {
	h := siteHarness(t, spec.SiteOptions{NotFoundFile: "404.html"}, map[string]string{
		"index.html": "home",
		"404.html":   "nothing here",
	})

	recorder := get(t, h, "http://shop.example/missing")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("the custom error page answered %d, want 404: a 200 gets it cached and "+
			"indexed as if it were a real page", recorder.Code)
	}
	if body := recorder.Body.String(); body != "nothing here" {
		t.Fatalf("served %q, want the customer's own error page", body)
	}
}

func TestSiteAnswersAMissWithSomethingReadable(t *testing.T) {
	h := siteHarness(t, spec.SiteOptions{}, map[string]string{"index.html": "home"})

	recorder := get(t, h, "http://shop.example/missing")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("a miss answered %d, want 404", recorder.Code)
	}
	if recorder.Body.Len() == 0 {
		t.Fatal("a miss produced an empty body, which renders as a blank frame")
	}
}

func TestSiteListsADirectoryOnlyWhenAsked(t *testing.T) {
	files := map[string]string{"docs/one.txt": "one", "docs/two.txt": "two"}

	closed := siteHarness(t, spec.SiteOptions{}, files)
	if code := get(t, closed, "http://shop.example/docs/").Code; code != http.StatusNotFound {
		t.Fatalf("a directory with no index answered %d with listings off, want 404: an "+
			"accidental listing publishes whatever the build left behind", code)
	}

	open := siteHarness(t, spec.SiteOptions{DirectoryListing: true}, files)
	recorder := get(t, open, "http://shop.example/docs/")
	if recorder.Code != http.StatusOK {
		t.Fatalf("a directory answered %d with listings on, want 200", recorder.Code)
	}
	body := recorder.Body.String()
	for _, wanted := range []string{"one.txt", "two.txt", "../"} {
		if !strings.Contains(body, wanted) {
			t.Errorf("the listing does not mention %q", wanted)
		}
	}
}

func TestSiteRefusesToLeaveTheReleaseDirectory(t *testing.T) {
	h := siteHarness(t, spec.SiteOptions{}, map[string]string{"index.html": "home"})

	// A file the release must never be able to reach, one level up from it.
	outside := filepath.Join(h.stateDir, sitesDirName, "7", "secret.txt")
	if err := os.WriteFile(outside, []byte("not for the internet"), 0o600); err != nil {
		t.Fatalf("writing the file that must stay unreachable: %v", err)
	}

	for _, target := range []string{
		"http://shop.example/../secret.txt",
		"http://shop.example/docs/../../secret.txt",
		"http://shop.example/%2e%2e/secret.txt",
		"http://shop.example//../secret.txt",
	} {
		recorder := get(t, h, target)
		if strings.Contains(recorder.Body.String(), "not for the internet") {
			t.Fatalf("%s escaped the release directory", target)
		}
	}
}

func TestSiteRefusesASymlinkOutOfTheRelease(t *testing.T) {
	h := siteHarness(t, spec.SiteOptions{}, map[string]string{"index.html": "home"})

	outside := filepath.Join(h.stateDir, "outside.txt")
	if err := os.WriteFile(outside, []byte("not for the internet"), 0o600); err != nil {
		t.Fatalf("writing the file that must stay unreachable: %v", err)
	}
	link := filepath.Join(h.stateDir, sitesDirName, "7", publishedLinkName, "escape.txt")
	if err := os.Symlink(outside, link); err != nil {
		// Creating a symlink needs a privilege on Windows that a developer's shell does
		// not have. The protection is the kernel's either way; skipping keeps the suite
		// runnable where it cannot be demonstrated.
		t.Skipf("this machine cannot create a symlink to test with: %v", err)
	}

	recorder := get(t, h, "http://shop.example/escape.txt")
	if strings.Contains(recorder.Body.String(), "not for the internet") {
		t.Fatal("a symlink in the release directory reached a file outside it")
	}
}

func TestSiteRefusesAMethodItCannotAnswer(t *testing.T) {
	h := siteHarness(t, spec.SiteOptions{}, map[string]string{"index.html": "home"})

	recorder := httptest.NewRecorder()
	h.edge.serve(recorder, httptest.NewRequest(http.MethodPost, "http://shop.example/", nil), false)
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("a POST to a static site answered %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); allow == "" {
		t.Fatal("a 405 without an Allow header does not tell the client what it may do")
	}
}

func TestSiteWithNothingPublishedSaysSo(t *testing.T) {
	h := newHarness(t)
	h.sync([]spec.Route{siteRoute("shop.example", "7")},
		[]spec.Workload{siteWorkload("7", spec.SiteOptions{})})

	recorder := get(t, h, "http://shop.example/")
	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("a site with no release answered %d, want 503: a 404 tells a search engine "+
			"to forget a site that is about to exist", recorder.Code)
	}
	if recorder.Body.Len() == 0 {
		t.Fatal("a site with no release produced an empty body")
	}
}
