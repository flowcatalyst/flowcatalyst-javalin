// Package frontend embeds the built Vue SPA (frontend/dist) into the
// Go binary and exposes it as an http.Handler. Sibling of
// bin/fc-dev/src/main.rs::FrontendAssets in flowcatalyst-rust.
//
// # Build dependency
//
// `//go:embed all:dist` requires `frontend/dist/` to exist at compile
// time. Fresh checkouts must run `make frontend` (or
// `cd frontend && pnpm install && pnpm build`) before `go build`.
// The Makefile's `build` target handles this automatically.
//
// # SPA fallback
//
// Vue Router's history mode means any path the server doesn't
// recognise should serve `index.html` so the SPA can route client-side.
// Handler() does this: exact-match asset under dist/ wins, otherwise
// fall through to index.html.
//
// # Mount order
//
// Mount Handler() AFTER all API routes (/api/*, /bff/*, /health/*,
// /monitoring/*, /warnings/*) so it never shadows the API surface. chi
// route precedence makes this trivial — register the API first, then
// `r.NotFound(frontend.Handler().ServeHTTP)` or
// `r.Mount("/", frontend.Handler())` last.
package frontend

import (
	"embed"
	"io/fs"
)

//go:embed all:dist
var distRoot embed.FS

// distFS is the rooted FS for serving — strips the leading `dist/`
// segment so request paths line up with embedded paths. Returns
// nil if the embed is empty (no `dist/` directory at build time)
// so the Handler can serve a clear "frontend not built" response
// instead of panicking.
var distFS = func() fs.FS {
	sub, err := fs.Sub(distRoot, "dist")
	if err != nil {
		return nil
	}
	if _, err := fs.Stat(sub, "index.html"); err != nil {
		return nil
	}
	return sub
}()
