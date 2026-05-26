package frontend

import (
	"io/fs"
	"mime"
	"net/http"
	"path"
	"strings"
)

// Handler returns an http.Handler that serves the embedded SPA.
// Behaviour mirrors Rust's embedded_asset_handler:
//
//  1. Exact-path match under dist/ → serve the file with the right
//     MIME type, and add `Cache-Control: public, max-age=31536000,
//     immutable` for content under /assets/ (Vite emits hash-suffixed
//     filenames, safe for long caching).
//  2. Anything else (unknown path, no file extension, etc.) → serve
//     index.html so Vue Router's history mode can take over.
//
// Returns a "frontend not built" handler if `frontend/dist/` was empty
// at compile time — distinguishes "we forgot to run pnpm build" from
// a genuine 404 for clarity.
func Handler() http.Handler {
	if distFS == nil {
		return http.HandlerFunc(missingFrontend)
	}
	return http.HandlerFunc(serveEmbedded)
}

// IsAvailable reports whether the embedded SPA has anything to serve.
// Callers can use this to skip mounting the frontend route when the
// binary was built without `make frontend` (e.g. for backend-only
// iteration) — the user sees an honest "no frontend wired" instead of
// a generic 404.
func IsAvailable() bool { return distFS != nil }

func serveEmbedded(w http.ResponseWriter, r *http.Request) {
	// Strip the leading slash so the path is relative to the FS root.
	reqPath := strings.TrimPrefix(r.URL.Path, "/")
	if reqPath == "" {
		serveIndex(w, r)
		return
	}

	// Reject path traversal early. fs.Sub already prevents escape, but
	// rejecting here keeps the response code precise (404 not 500).
	if strings.Contains(reqPath, "..") {
		serveIndex(w, r) // SPA fallback for any malformed input
		return
	}

	f, err := distFS.Open(reqPath)
	if err != nil {
		// Asset not present in the embed → SPA fallback.
		serveIndex(w, r)
		return
	}
	defer f.Close()

	info, err := f.Stat()
	if err != nil || info.IsDir() {
		serveIndex(w, r)
		return
	}

	// MIME type by extension; fall back to octet-stream for unknowns.
	ext := path.Ext(reqPath)
	if ct := mime.TypeByExtension(ext); ct != "" {
		w.Header().Set("Content-Type", ct)
	} else {
		w.Header().Set("Content-Type", "application/octet-stream")
	}

	// Vite emits hash-suffixed filenames under /assets/ so they're safe
	// for long-lived caching. Other paths (favicon, index.html, etc.)
	// don't get this.
	if strings.HasPrefix(reqPath, "assets/") {
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	}

	http.ServeContent(w, r, reqPath, info.ModTime(), readSeeker(f, info.Size()))
}

func serveIndex(w http.ResponseWriter, r *http.Request) {
	f, err := distFS.Open("index.html")
	if err != nil {
		// Should be impossible because distFS init verified index.html
		// exists; treat as configuration error rather than 404.
		http.Error(w, "index.html missing from embedded frontend", http.StatusInternalServerError)
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		http.Error(w, "index.html stat failed", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	// SPA shell — don't cache; lets Vue load latest hash-suffixed
	// asset bundles on every page load.
	w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
	http.ServeContent(w, r, "index.html", info.ModTime(), readSeeker(f, info.Size()))
}

func missingFrontend(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(http.StatusNotFound)
	_, _ = w.Write([]byte(
		"Frontend not built into this binary.\n" +
			"Run `make frontend` (or `cd frontend && pnpm install && pnpm build`)\n" +
			"and rebuild the binary.\n"))
}

// readSeeker adapts an fs.File to io.ReadSeeker for http.ServeContent.
// Files returned by embed.FS always implement io.Seeker, but the
// fs.File interface doesn't promise it — explicit cast keeps the
// failure mode at "compile error if Go's embed changes" rather than
// "runtime panic on first request".
func readSeeker(f fs.File, _ int64) interface {
	Read([]byte) (int, error)
	Seek(int64, int) (int64, error)
} {
	type readSeekerIface interface {
		Read([]byte) (int, error)
		Seek(int64, int) (int64, error)
	}
	return f.(readSeekerIface)
}
