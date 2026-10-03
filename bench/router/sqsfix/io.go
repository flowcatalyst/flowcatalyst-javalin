package main

import (
	"io"
	"net/http"
	"strings"
)

func fmt_copy(b *strings.Builder, r *http.Request) (int64, error) { return io.Copy(b, r.Body) }
