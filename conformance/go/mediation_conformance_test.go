// Package router_test runs conformance/mediation-outcomes.json against the
// real Go mediator. This file belongs in flowcatalyst-go/internal/router —
// see conformance/go/README.md for how to install and run it there. It
// cannot be built or run from this checkout: it imports the router module,
// which does not exist here. Everything below is written from careful
// reading of the Go source at commit 819b390, not from a passing run — the
// README says so plainly, and so does every claim in this file's comments
// that starts with "verified" vs. one that starts with "by reading".
//
// # Phase 1 only
//
// This asserts outcome, statusCode, delaySeconds, flushGroup, breaker,
// warning and httpCallMade. It deliberately does NOT assert disposition —
// see go-runner.md "Phase 2": the decision lives in an inline switch inside
// pool.go's delivery loop with nothing a test can call, and extracting it
// is a Go change out of scope here.
//
// # The corpus is not a Go-compatibility harness
//
// A row that fails here is a question with three possible answers — Go is
// wrong, Java is wrong, or the corpus is wrong — not a Go bug by default.
// conformance/README.md and go-runner.md's "When a row fails" section say
// which of the three to reach for. Failure messages below point back at
// those files rather than telling the reader to "fix Go".
package router_test

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flowcatalyst/flowcatalyst-go/internal/common"
	"github.com/flowcatalyst/flowcatalyst-go/internal/router"
)

// defaultCorpusPath is relative to this file's directory once installed at
// internal/router/mediation_conformance_test.go, per go-runner.md. The
// corpus lives in the JAVA repo and is read, not copied — two copies drift
// and then prove nothing.
const defaultCorpusPath = "../flowcatalyst-javalin/conformance/mediation-outcomes.json"

// ---- corpus shape --------------------------------------------------------

type fixtureCorpus struct {
	Cases []fixtureCase `json:"cases"`
}

type fixtureCase struct {
	ID         string             `json:"id"`
	Given      fixtureGiven       `json:"given"`
	Expect     fixtureExpect      `json:"expect"`
	Divergence *fixtureDivergence `json:"divergence"`
}

type fixtureGiven struct {
	Kind    string            `json:"kind"`
	Status  int               `json:"status"`
	Body    string            `json:"body"`
	Headers map[string]string `json:"headers"`
}

// Pointer fields distinguish "the corpus didn't assert this" from "the
// corpus asserted the zero value" — delaySeconds:0 and flushGroup:false are
// both meaningful expectations, not absence.
type fixtureExpect struct {
	Outcome      string `json:"outcome"`
	StatusCode   int    `json:"statusCode"`
	DelaySeconds *int   `json:"delaySeconds"`
	FlushGroup   *bool  `json:"flushGroup"`
	HTTPCallMade *bool  `json:"httpCallMade"`
	Breaker      string `json:"breaker"`
	Warning      string `json:"warning"`
	// Disposition and Metric are part of the corpus's case shape and are
	// read here so a struct-tag typo would still show up in `go vet`, but
	// Phase 1 intentionally never asserts them.
	Disposition string `json:"disposition"`
	Metric      string `json:"metric"`
}

type fixtureDivergence struct {
	Correct string `json:"correct"`
	Basis   string `json:"basis"`
}

func loadCorpus(t *testing.T) fixtureCorpus {
	t.Helper()
	path := os.Getenv("FC_CONFORMANCE_CORPUS")
	if path == "" {
		path = defaultCorpusPath
	}
	data, err := os.ReadFile(path)
	if err != nil {
		// The Go repo must build and test standalone even when the Java
		// repo isn't checked out alongside it (a CI runner building only
		// flowcatalyst-go, a fresh clone, etc.) — skip, don't fail.
		t.Skipf("conformance corpus not found at %q (set FC_CONFORMANCE_CORPUS to point at a checkout of flowcatalyst-javalin): %v", path, err)
	}
	var c fixtureCorpus
	if err := json.Unmarshal(data, &c); err != nil {
		t.Fatalf("corpus %q: invalid JSON: %v", path, err)
	}
	if len(c.Cases) == 0 {
		// A corpus that silently shrinks to nothing would otherwise pass,
		// loudly wrong.
		t.Fatalf("corpus %q: no cases", path)
	}
	return c
}

// ---- the runner -----------------------------------------------------------

func TestMediationConformance(t *testing.T) {
	corpus := loadCorpus(t)
	for _, tc := range corpus.Cases {
		tc := tc
		t.Run(tc.ID, func(t *testing.T) {
			// Each subtest gets its own breaker registry, mediator, and
			// HTTP server, so there is no shared mutable state between
			// cases — safe to parallelize, which matters because several
			// rows are retryable and each retry sleeps for real
			// (DevMediatorConfig's RetryDelays), adding ~3s apiece.
			t.Parallel()
			runCase(t, tc)
		})
	}
}

func runCase(t *testing.T, tc fixtureCase) {
	breakers := router.NewBreakerRegistry(router.DefaultBreakerConfig())
	mediator := router.NewHTTPMediator(router.DevMediatorConfig(), breakers)
	defer mediator.Close()

	// WarningService IS observable from outside the router package: it's
	// exported, and SetWarnings is the same opt-in hook production wires up
	// (server startup calls it once; here each subtest wires its own).
	// warnConfig logs unconditionally and only ALSO calls into this service
	// when one is set — see mediator.go's warnConfig and SetWarnings docs.
	// So the "warnings not observable" caveat go-runner.md asked us to
	// check for does NOT apply: nothing needs to change in Go for this
	// column.
	warnings := router.NewWarningService(router.WarningServiceConfig{})
	mediator.SetWarnings(warnings)

	var calls atomic.Int64
	server := httptest.NewServer(targetHandler(tc.Given, &calls))
	defer server.Close()

	target := server.URL + "/hook"
	mediationType := common.MediationTypeHTTP

	switch tc.Given.Kind {
	case "response":
		// Handled entirely by targetHandler, keyed on tc.Given.

	case "unreachableTarget":
		// A port nothing is listening on. Bind-then-close so the OS
		// guarantees it was free at the moment of binding, rather than a
		// hopeful constant that might collide with something else on the
		// box.
		target = fmt.Sprintf("http://127.0.0.1:%d/hook", freePort(t))

	case "malformedTargetUrl":
		// No host — HostKeyFromURL (host_pool.go) rejects this before any
		// dial is attempted.
		target = "http:///no-host"

	case "unsupportedMediationType":
		mediationType = common.MediationType("SQS")

	case "breakerOpen":
		b := breakers.Get(target)
		for b.State() != router.CircuitOpen {
			b.RecordFailure()
		}

	default:
		t.Fatalf("case %q: unknown given.kind %q (known: response, unreachableTarget, malformedTargetUrl, unsupportedMediationType, breakerOpen)", tc.ID, tc.Given.Kind)
	}

	msg := &common.Message{
		ID:              "conformance-1",
		MediationType:   mediationType,
		MediationTarget: target,
		DispatchMode:    common.DispatchNextOnError,
	}

	// A safety net, not a claim about production behaviour: DevMediatorConfig
	// already bounds each attempt to 30s via http.Client.Timeout, and
	// Mediate honours ctx.Done() between retries. Two minutes is generous
	// headroom above the worst case (3 attempts * 30s + 2 backoff waits)
	// so a genuinely stuck case fails the test instead of hanging `go test`.
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	before := breakers.Get(target).Stats()
	outcome := mediator.Mediate(ctx, msg)
	after := breakers.Get(target).Stats()

	var mismatches []mismatch
	assertOutcome(&mismatches, tc, outcome)
	assertBreaker(&mismatches, tc, before, after)
	assertWarning(&mismatches, tc, warnings)
	if tc.Expect.HTTPCallMade != nil {
		assertHTTPCallMade(&mismatches, tc, calls.Load())
	}

	report(t, tc, mismatches)
}

// targetHandler answers `given` for "response" cases. For every other kind
// it answers a plain 200 — it should never actually be hit (unreachable and
// malformed-URL cases never dial this server; the pre-flight-rejected and
// breaker-open cases return before any HTTP call), so a 200 here is a
// canary: if a mismatch ever reports statusCode 200 unexpectedly, it means
// this server was called when the case expected it not to be — a bug in
// the test's own kind-handling, not in Go.
func targetHandler(given fixtureGiven, calls *atomic.Int64) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		_, _ = io.Copy(io.Discard, r.Body)
		_ = r.Body.Close()

		status := http.StatusOK
		body := ""
		var headers map[string]string
		if given.Kind == "response" {
			status = given.Status
			body = given.Body
			headers = given.Headers
		}

		if status >= 100 && status <= 199 {
			// net/http's own ResponseWriter cannot terminate a response on
			// a bare 1xx: WriteHeader(1xx) is purely informational
			// (net/http/server.go: "wroteHeader" — a bool meaning "a
			// NON-1xx header has been written" — stays false), so if the
			// handler returns without ever sending a terminal header, the
			// server silently completes the response with 200 OK. That
			// would test something else entirely (an implicit 200, not a
			// bare 1xx), so hijack the connection and write the raw status
			// line ourselves — this is what an actual external server
			// answering with only a 1xx and nothing else looks like on the
			// wire. Verified empirically against this Go toolchain: the
			// client then sees a broken connection (persistConn.readLoop
			// waits for a terminal response after any non-101 1xx per
			// transport.go's is1xxNonTerminal, and gets EOF instead), which
			// is common.ErrorConnection — see the note on
			// "unexpected-status-1xx" below for why that value, despite
			// disagreeing with go-runner.md's "default arm" account, is
			// what this test actually asserts.
			hj, ok := w.(http.Hijacker)
			if !ok {
				panic("httptest server does not support hijacking; cannot simulate a bare 1xx")
			}
			conn, _, err := hj.Hijack()
			if err != nil {
				return
			}
			defer conn.Close()
			fmt.Fprintf(conn, "HTTP/1.1 %d Informational\r\n\r\n", status)
			return
		}

		for k, v := range headers {
			w.Header().Set(k, v)
		}
		w.WriteHeader(status)
		if body != "" {
			_, _ = io.WriteString(w, body)
		}
	}
}

func freePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("allocate a free port: %v", err)
	}
	port := l.Addr().(*net.TCPAddr).Port
	_ = l.Close()
	return port
}

// ---- assertions -------------------------------------------------------

type mismatch struct {
	field    string
	expected string
	actual   string
}

func (m mismatch) String() string {
	return fmt.Sprintf("%s: expected %s, got %s", m.field, m.expected, m.actual)
}

// outcomeName maps common.MediationResult to the corpus's implementation-
// neutral vocabulary. go-runner.md's table, made exhaustive.
func outcomeName(r common.MediationResult) string {
	switch r {
	case common.MediationSuccess:
		return "Success"
	case common.MediationDeferred:
		return "Deferred"
	case common.MediationErrorConfig:
		return "ErrorConfig"
	case common.MediationErrorProcess:
		return "ErrorProcess"
	case common.MediationErrorConnection:
		return "ErrorConnection"
	case common.MediationRateLimited:
		return "RateLimited"
	case common.MediationCircuitOpen:
		return "CircuitOpen"
	default:
		return fmt.Sprintf("Unknown(%d)", r)
	}
}

func assertOutcome(mismatches *[]mismatch, tc fixtureCase, outcome common.MediationOutcome) {
	if got := outcomeName(outcome.Result); got != tc.Expect.Outcome {
		*mismatches = append(*mismatches, mismatch{"outcome", tc.Expect.Outcome, got})
	}
	if outcome.StatusCode != tc.Expect.StatusCode {
		*mismatches = append(*mismatches, mismatch{"statusCode",
			strconv.Itoa(tc.Expect.StatusCode), strconv.Itoa(outcome.StatusCode)})
	}
	if tc.Expect.DelaySeconds != nil && outcome.DelaySeconds != *tc.Expect.DelaySeconds {
		*mismatches = append(*mismatches, mismatch{"delaySeconds",
			strconv.Itoa(*tc.Expect.DelaySeconds), strconv.Itoa(outcome.DelaySeconds)})
	}
	if tc.Expect.FlushGroup != nil && outcome.FlushGroup != *tc.Expect.FlushGroup {
		*mismatches = append(*mismatches, mismatch{"flushGroup",
			strconv.FormatBool(*tc.Expect.FlushGroup), strconv.FormatBool(outcome.FlushGroup)})
	}
}

// assertBreaker checks the DELTA across the single Mediate call, not
// cumulative counters — a fresh registry per subtest makes before==0 always,
// but the delta form is what go-runner.md specifies and it also protects
// against ever sharing a registry across cases by accident later.
func assertBreaker(mismatches *[]mismatch, tc fixtureCase, before, after router.BreakerStats) {
	successDelta := after.Successes - before.Successes
	failureDelta := after.Failures - before.Failures

	var wantSuccess, wantFailure uint64
	switch tc.Expect.Breaker {
	case "success":
		wantSuccess, wantFailure = 1, 0
	case "failure":
		wantSuccess, wantFailure = 0, 1
	case "neither", "none":
		wantSuccess, wantFailure = 0, 0
	default:
		panic(fmt.Sprintf("case %q: unknown breaker expectation %q", tc.ID, tc.Expect.Breaker))
	}

	if successDelta != wantSuccess || failureDelta != wantFailure {
		*mismatches = append(*mismatches, mismatch{
			"breaker",
			fmt.Sprintf("%s (successes+%d failures+%d)", tc.Expect.Breaker, wantSuccess, wantFailure),
			fmt.Sprintf("successes+%d failures+%d", successDelta, failureDelta),
		})
	}
}

// assertWarning reads back everything the mediator raised into the
// WarningService wired up for this one subtest. A permanent ACK-drop must
// warn (the warning is the only trace the deleted message leaves); a
// retryable outcome must not (warning per attempt would flood the store
// during an ordinary outage) — conformance/README.md's warning rule.
func assertWarning(mismatches *[]mismatch, tc fixtureCase, warnings *router.WarningService) {
	all := warnings.All()
	if tc.Expect.Warning == "none" {
		if len(all) != 0 {
			*mismatches = append(*mismatches, mismatch{"warning", "none", summarizeWarnings(all)})
		}
		return
	}
	if len(all) != 1 {
		*mismatches = append(*mismatches, mismatch{"warning", tc.Expect.Warning + "/CONFIGURATION", summarizeWarnings(all)})
		return
	}
	w := all[0]
	if string(w.Severity) != tc.Expect.Warning || w.Category != router.WarningCategoryConfiguration {
		*mismatches = append(*mismatches, mismatch{"warning",
			tc.Expect.Warning + "/CONFIGURATION", string(w.Severity) + "/" + string(w.Category)})
	}
}

func summarizeWarnings(all []router.Warning) string {
	if len(all) == 0 {
		return "none"
	}
	parts := make([]string, len(all))
	for i, w := range all {
		parts[i] = string(w.Severity) + "/" + string(w.Category)
	}
	return strings.Join(parts, ", ")
}

func assertHTTPCallMade(mismatches *[]mismatch, tc fixtureCase, calls int64) {
	got := calls > 0
	want := *tc.Expect.HTTPCallMade
	if got != want {
		*mismatches = append(*mismatches, mismatch{"httpCallMade", strconv.FormatBool(want), strconv.FormatBool(got)})
	}
}

// ---- reporting ----------------------------------------------------------

// report decides, given the corpus's own divergence block, whether a set of
// mismatches is a known/argued disagreement (log it, keep the build green)
// or a real question (fail the build). It never edits Go to make a red row
// pass — see go-runner.md "When a row fails".
func report(t *testing.T, tc fixtureCase, mismatches []mismatch) {
	if len(mismatches) == 0 {
		return
	}

	lines := make([]string, len(mismatches))
	for i, m := range mismatches {
		lines[i] = "  - " + m.String()
	}
	detail := fmt.Sprintf("case %q diverged from the corpus:\n%s", tc.ID, strings.Join(lines, "\n"))

	if tc.Divergence != nil && tc.Divergence.Correct == "java" {
		// The corpus itself argues Java is right and Go is wrong here — see
		// `divergence.basis` in mediation-outcomes.json. Re-discovering a
		// tracked defect is not new information, so this does not fail the
		// build; it's logged so the divergence stays visible without
		// blocking CI on a fix that has to happen in Go, deliberately, not
		// as a side effect of a red test.
		t.Logf("EXPECTED DIVERGENCE (corpus says java is correct — this is a KNOWN, TRACKED Go defect, not new information):\n%s\nbasis: %s",
			detail, tc.Divergence.Basis)
		return
	}

	// Anything else — no divergence block at all, or divergence.correct ==
	// "both"/"go" — is either a genuine Go bug this run just found, or a
	// row the corpus has not yet reasoned about. Do NOT assume Go is wrong:
	// check conformance/README.md ("Divergences") and go-runner.md ("When a
	// row fails") before touching any code. If Go turns out to be right and
	// the corpus's `expect` block is stale, fix the row and its `basis`,
	// not the code.
	t.Errorf("%s\nThis failure is not automatically a Go bug. Read conformance/README.md ('Divergences') and conformance/go-runner.md ('When a row fails') before changing Go — the row may need a new `divergence` block instead, or the corpus may be wrong.", detail)
}
