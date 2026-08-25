// Command corpus_selfcheck validates the shape of
// conformance/mediation-outcomes.json on its own, with no dependency on the
// Go router module. It is deliberately `package main` in a directory that
// is NOT `internal/router` so it builds and runs right here, in the Java
// repo, without a Go module for flowcatalyst-go at all.
//
// mediation_conformance_test.go (the file meant to be dropped into
// flowcatalyst-go's internal/router package) cannot be compiled or run from
// this checkout — it imports the router package. This program is the one
// piece of the Go half that actually gets built and run as part of this
// task; see conformance/go/README.md for what that does and does not prove.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"sort"
)

// corpus mirrors conformance/mediation-outcomes.json. Deliberately a
// second, independent struct definition from mediation_conformance_test.go
// rather than a shared package: the two files must never share a Go module
// (this one has none), so there is nothing to import from.
type corpus struct {
	Cases []caseEntry `json:"cases"`
}

type caseEntry struct {
	ID         string          `json:"id"`
	Given      json.RawMessage `json:"given"`
	Expect     json.RawMessage `json:"expect"`
	Divergence *divergence     `json:"divergence"`
}

type givenShape struct {
	Kind string `json:"kind"`
}

// expectPresence captures which optional keys exist, and holds the required
// ones typed so "present but null" and "absent" are told apart.
type expectPresence struct {
	Outcome     *string `json:"outcome"`
	StatusCode  *int    `json:"statusCode"`
	Warning     *string `json:"warning"`
	Disposition *string `json:"disposition"`
	Breaker     *string `json:"breaker"`
	Metric      *string `json:"metric"`
}

type divergence struct {
	Correct string `json:"correct"`
	Basis   string `json:"basis"`
	Go      string `json:"go,omitempty"`
	Java    string `json:"java,omitempty"`
	Tracked string `json:"tracked,omitempty"`
	Owner   string `json:"owner,omitempty"`
}

// knownKinds mirrors the given.kind table in conformance/README.md and
// go-runner.md. A case naming any other kind is a corpus authoring error —
// no runner (Java or Go) would know what to do with it.
var knownKinds = map[string]bool{
	"response":                 true,
	"unreachableTarget":        true,
	"malformedTargetUrl":       true,
	"unsupportedMediationType": true,
	"breakerOpen":              true,
}

var knownCorrect = map[string]bool{"java": true, "go": true, "both": true}

func main() {
	path := flag.String("corpus", defaultCorpusPath(), "path to mediation-outcomes.json")
	flag.Parse()

	data, err := os.ReadFile(*path)
	if err != nil {
		fmt.Fprintf(os.Stderr, "corpus_selfcheck: cannot read %s: %v\n", *path, err)
		os.Exit(1)
	}

	var c corpus
	if err := json.Unmarshal(data, &c); err != nil {
		fmt.Fprintf(os.Stderr, "corpus_selfcheck: %s is not valid JSON: %v\n", *path, err)
		os.Exit(1)
	}

	var problems []string
	ids := map[string]int{}
	kindCounts := map[string]int{}
	var divergences []divergenceRow

	for i, tc := range c.Cases {
		loc := fmt.Sprintf("case[%d]", i)
		if tc.ID != "" {
			loc = fmt.Sprintf("case %q", tc.ID)
		}

		if tc.ID == "" {
			problems = append(problems, loc+": missing id")
		} else {
			ids[tc.ID]++
		}

		var given givenShape
		if err := json.Unmarshal(tc.Given, &given); err != nil {
			problems = append(problems, fmt.Sprintf("%s: given is not an object: %v", loc, err))
		} else if given.Kind == "" {
			problems = append(problems, loc+": given.kind is missing")
		} else if !knownKinds[given.Kind] {
			problems = append(problems, fmt.Sprintf("%s: given.kind %q is not one of the known kinds (response, unreachableTarget, malformedTargetUrl, unsupportedMediationType, breakerOpen)", loc, given.Kind))
		} else {
			kindCounts[given.Kind]++
		}

		var expect expectPresence
		if err := json.Unmarshal(tc.Expect, &expect); err != nil {
			problems = append(problems, fmt.Sprintf("%s: expect is not an object: %v", loc, err))
		} else {
			// These six are load-bearing for every runner (Phase 1 needs
			// outcome/statusCode/breaker/warning; Phase 2 needs
			// disposition; metric is asserted by neither runner today but
			// is part of the documented case shape and every row supplies
			// it, so its absence would silently narrow the contract).
			required := map[string]bool{
				"outcome":     expect.Outcome != nil,
				"statusCode":  expect.StatusCode != nil,
				"warning":     expect.Warning != nil,
				"disposition": expect.Disposition != nil,
				"breaker":     expect.Breaker != nil,
				"metric":      expect.Metric != nil,
			}
			var missing []string
			for field, present := range required {
				if !present {
					missing = append(missing, field)
				}
			}
			if len(missing) > 0 {
				sort.Strings(missing)
				problems = append(problems, fmt.Sprintf("%s: expect missing required field(s): %v", loc, missing))
			}
		}

		if tc.Divergence != nil {
			d := *tc.Divergence
			if d.Correct == "" || d.Basis == "" {
				problems = append(problems, fmt.Sprintf("%s: divergence is missing `correct` and/or `basis` — an unargued divergence is an open question, not a decision (conformance/README.md)", loc))
			} else if !knownCorrect[d.Correct] {
				problems = append(problems, fmt.Sprintf("%s: divergence.correct %q must be one of java/go/both", loc, d.Correct))
			}
			divergences = append(divergences, divergenceRow{id: tc.ID, correct: d.Correct, basis: d.Basis})
		}
	}

	for id, count := range ids {
		if count > 1 {
			problems = append(problems, fmt.Sprintf("id %q is used by %d cases — ids must be unique", id, count))
		}
	}

	printSummary(c, kindCounts, divergences)

	if len(problems) > 0 {
		fmt.Fprintln(os.Stderr, "\nFAIL — corpus_selfcheck found problems:")
		for _, p := range problems {
			fmt.Fprintln(os.Stderr, "  - "+p)
		}
		os.Exit(1)
	}
	fmt.Println("\nPASS — corpus is well-formed.")
}

type divergenceRow struct {
	id      string
	correct string
	basis   string
}

func printSummary(c corpus, kindCounts map[string]int, divergences []divergenceRow) {
	fmt.Printf("cases: %d\n", len(c.Cases))

	fmt.Println("\nby given.kind:")
	kinds := make([]string, 0, len(kindCounts))
	for k := range kindCounts {
		kinds = append(kinds, k)
	}
	sort.Strings(kinds)
	for _, k := range kinds {
		fmt.Printf("  %-26s %d\n", k, kindCounts[k])
	}

	fmt.Printf("\ndivergences: %d\n", len(divergences))
	sort.Slice(divergences, func(i, j int) bool { return divergences[i].id < divergences[j].id })
	for _, d := range divergences {
		basis := d.basis
		if len(basis) > 88 {
			basis = basis[:85] + "..."
		}
		fmt.Printf("  [%-6s] %-38s %s\n", d.correct, d.id, basis)
	}
}

// defaultCorpusPath matches the path mediation_conformance_test.go uses
// when dropped into flowcatalyst-go/internal/router: relative from there,
// "../flowcatalyst-javalin/conformance/mediation-outcomes.json". Run from
// this file's own directory (conformance/go), the corpus is one level up.
func defaultCorpusPath() string {
	return "../mediation-outcomes.json"
}
