// bench-router-sink: the "mediation_target" the router POSTs {"messageId":"<id>"} to
// (docs/spec/router.md §6.1). Answers 200 {"ok":true} on /hook and counts what it saw.
package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/net/http2"
	"golang.org/x/net/http2/h2c"
)

var (
	count   int64 // atomic: total /hook hits since last reset
	firstNs int64 // atomic: ns of the first hit since reset (0 = none yet)
	lastNs  int64 // atomic: ns of the most recent hit since reset

	statsMu      sync.Mutex // guards protoCounts/statusCounts only; hot path is atomics above
	protoCounts  = map[string]int64{}
	statusCounts = map[int]int64{}

	// windowSec/windowCnt: a coarse "requests in the current wall-clock second" counter,
	// good enough for a live throughput readout — not a precise sliding window.
	windowSec int64
	windowCnt int64

	delayMs = envInt("SINK_DELAY_MS", 0)

	// /config: the router config-URL document (docs/spec/router.md §8.1). POOL_CONCURRENCY
	// and QUEUE_URI are set by run.sh; the field names (processingPools/code/concurrency,
	// queues/queueUri/queueName/connections/visibilityTimeout) match both the Go wire format
	// (internal/common/config.go) and the Java one (router/config/{PoolSpec,QueueConfig}.java).
	poolConcurrency = envInt("POOL_CONCURRENCY", 64)
	queueURI        = os.Getenv("QUEUE_URI")
	// QUEUES: how many independent queues (BENCH-1..BENCH-n) /config advertises, all routed
	// to the single BENCH pool. Queue identity on both routers is the queueName field, not the
	// URI (docs/spec/router.md §7.1/§7.3 — Postgres Poll filters `WHERE queue_name = $1` on
	// QueueConfig.Name/queueName; the URI only selects the backend/connection), so N entries
	// sharing one queueUri but distinct queueName values give N independent poll loops against
	// the same database — queue-level parallelism past the one-consumer-per-queue ceiling.
	numQueues = envInt("QUEUES", 1)
)

func envInt(name string, def int) int {
	v := os.Getenv(name)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}

func bumpFirst(now int64) {
	if atomic.CompareAndSwapInt64(&firstNs, 0, now) {
		return
	}
	// someone else may have set it a moment later than "now"; keep the true minimum.
	for {
		cur := atomic.LoadInt64(&firstNs)
		if cur != 0 && cur <= now {
			return
		}
		if atomic.CompareAndSwapInt64(&firstNs, cur, now) {
			return
		}
	}
}

func bumpLast(now int64) {
	for {
		cur := atomic.LoadInt64(&lastNs)
		if now <= cur {
			return
		}
		if atomic.CompareAndSwapInt64(&lastNs, cur, now) {
			return
		}
	}
}

func bumpWindow(now int64) {
	sec := now / int64(time.Second)
	for {
		cur := atomic.LoadInt64(&windowSec)
		if cur == sec {
			atomic.AddInt64(&windowCnt, 1)
			return
		}
		if atomic.CompareAndSwapInt64(&windowSec, cur, sec) {
			atomic.StoreInt64(&windowCnt, 1)
			return
		}
	}
}

func hookHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if delayMs > 0 {
		time.Sleep(time.Duration(delayMs) * time.Millisecond)
	}
	now := time.Now().UnixNano()
	atomic.AddInt64(&count, 1)
	bumpFirst(now)
	bumpLast(now)
	bumpWindow(now)

	statsMu.Lock()
	protoCounts[r.Proto]++
	statusCounts[http.StatusOK]++
	statsMu.Unlock()

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"ok":true}`))
}

type statsResponse struct {
	Count        int64            `json:"count"`
	FirstNs      int64            `json:"first_ns"`
	LastNs       int64            `json:"last_ns"`
	ProtoCounts  map[string]int64 `json:"proto_counts"`
	StatusCounts map[int]int64    `json:"status_counts"`
	PerSecLast1s int64            `json:"per_sec_last_1s"`
}

func statsHandler(w http.ResponseWriter, r *http.Request) {
	nowSec := time.Now().UnixNano() / int64(time.Second)
	sec := atomic.LoadInt64(&windowSec)
	var perSec int64
	if sec == nowSec {
		perSec = atomic.LoadInt64(&windowCnt)
	}

	statsMu.Lock()
	protoCopy := make(map[string]int64, len(protoCounts))
	for k, v := range protoCounts {
		protoCopy[k] = v
	}
	statusCopy := make(map[int]int64, len(statusCounts))
	for k, v := range statusCounts {
		statusCopy[k] = v
	}
	statsMu.Unlock()

	resp := statsResponse{
		Count:        atomic.LoadInt64(&count),
		FirstNs:      atomic.LoadInt64(&firstNs),
		LastNs:       atomic.LoadInt64(&lastNs),
		ProtoCounts:  protoCopy,
		StatusCounts: statusCopy,
		PerSecLast1s: perSec,
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(resp)
}

func resetHandler(w http.ResponseWriter, r *http.Request) {
	atomic.StoreInt64(&count, 0)
	atomic.StoreInt64(&firstNs, 0)
	atomic.StoreInt64(&lastNs, 0)
	atomic.StoreInt64(&windowSec, 0)
	atomic.StoreInt64(&windowCnt, 0)
	statsMu.Lock()
	protoCounts = map[string]int64{}
	statusCounts = map[int]int64{}
	statsMu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`{"reset":true}`))
}

type poolConfig struct {
	Code        string `json:"code"`
	Concurrency int    `json:"concurrency"`
}

type queueConfig struct {
	QueueURI          string `json:"queueUri"`
	QueueName         string `json:"queueName"`
	Connections       int    `json:"connections"`
	VisibilityTimeout int    `json:"visibilityTimeout"`
}

type routerConfig struct {
	ProcessingPools []poolConfig  `json:"processingPools"`
	Queues          []queueConfig `json:"queues"`
}

func configHandler(w http.ResponseWriter, r *http.Request) {
	n := numQueues
	if n < 1 {
		n = 1
	}
	queues := make([]queueConfig, n)
	for i := 0; i < n; i++ {
		// QUEUE_URI is used verbatim for every entry (the Postgres broker: one database,
		// queue_name alone tells consumers apart) UNLESS it contains a "%d" placeholder, in
		// which case each queue gets its own URI (the SQS broker: one queue = one URL, e.g.
		// https://sqs.us-east-1.amazonaws.com/000000000000/BENCH-%d).
		// ReplaceAll (not fmt.Sprintf) because the NATS URI template needs the queue number
		// substituted twice (stream=BENCH%d and subject=bench.%d.>) — fmt.Sprintf with two %d
		// verbs and one arg would error on the second (%!d(MISSING)). Sprintf's single-%d SQS
		// template (…/BENCH-%d) is a strict subset of this, so this is backward compatible.
		uri := queueURI
		if strings.Contains(uri, "%d") {
			uri = strings.ReplaceAll(uri, "%d", strconv.Itoa(i+1))
		}
		queues[i] = queueConfig{
			QueueURI: uri, QueueName: "BENCH-" + strconv.Itoa(i+1),
			Connections: 1, VisibilityTimeout: 120,
		}
	}
	cfg := routerConfig{
		// One pool_code ("BENCH") on every message's payload regardless of which queue it
		// came from — all N queues route to the same shared worker pool.
		ProcessingPools: []poolConfig{{Code: "BENCH", Concurrency: poolConcurrency}},
		Queues:          queues,
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(cfg)
}

func main() {
	mux := http.NewServeMux()
	mux.HandleFunc("/hook", hookHandler)
	mux.HandleFunc("/stats", statsHandler)
	mux.HandleFunc("/reset", resetHandler)
	mux.HandleFunc("/config", configHandler)

	var handler http.Handler = mux
	if os.Getenv("SINK_H2C") == "1" {
		handler = h2c.NewHandler(mux, &http2.Server{})
		log.Printf("sink: listening on :9000 (h2c prior-knowledge cleartext enabled), SINK_DELAY_MS=%d, /config pool=BENCH concurrency=%d queues=%d queueUri=%s", delayMs, poolConcurrency, numQueues, queueURI)
	} else {
		log.Printf("sink: listening on :9000 (HTTP/1.1), SINK_DELAY_MS=%d, /config pool=BENCH concurrency=%d queues=%d queueUri=%s", delayMs, poolConcurrency, numQueues, queueURI)
	}

	srv := &http.Server{
		Addr:    ":9000",
		Handler: handler,
	}
	log.Fatal(srv.ListenAndServe())
}
