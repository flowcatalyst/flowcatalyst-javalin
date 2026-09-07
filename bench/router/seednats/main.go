// seednats: a one-shot bulk producer that fills TOTAL messages across QUEUES NATS JetStream
// streams (BENCH1..BENCHn) before the router container ever starts — the NATS equivalent of
// run.sh's seed_sql() (Postgres) and seedsqs (SQS/LocalStack). The streams and durable
// consumers are provisioned separately by run.sh via the `nats` CLI (natsio/nats-box) with the
// exact configuration the router's own create-or-update provisioning must match (§7.4); this
// tool only publishes.
//
// Body shape matches the other brokers exactly (docs/spec/router.md §2.1, §7.4 Publish):
// {"id":...,"poolCode":"BENCH","mediationType":"HTTP","mediationTarget":...,"dispatchMode":"IMMEDIATE"}
//
// Publish subject per queue n: bench.<n>.BENCH (§7.4: "subject = filter with trailing .>/.*
// replaced by .<poolCode>" — the filter for stream BENCHn is bench.<n>.>, poolCode is BENCH).
//
// Uses JetStream's async publish (js.PublishAsync), NOT core nc.Publish. An earlier version of
// this tool used core publish (fire-and-forget, no PubAck) for raw throughput and silently lost
// messages under load — a real run reported sent=50000 but the stream held only 32999
// (confirmed via `nats stream info --json` before the router ever started; see RESULTS.md "NATS
// JetStream"). PublishAsync gets a PubAck per message (so loss is detected, not silent) while
// still pipelining up to WithPublishAsyncMaxPending outstanding requests for throughput.
package main

import (
	"fmt"
	"log"
	"os"
	"strconv"
	"time"

	"github.com/nats-io/nats.go"
	"github.com/nats-io/nats.go/jetstream"
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

func envStr(name, def string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return def
}

func main() {
	total := envInt("TOTAL", 50000)
	nqueues := envInt("QUEUES", 1)
	natsURL := envStr("NATS_URL", "nats://127.0.0.1:4222")
	target := os.Getenv("MEDIATION_TARGET") // e.g. http://172.30.0.11:9000/hook
	maxPending := envInt("MAX_PENDING", 4096)

	if target == "" {
		log.Fatal("MEDIATION_TARGET is required")
	}

	nc, err := nats.Connect(natsURL,
		nats.Timeout(10*time.Second),
		nats.MaxReconnects(5),
	)
	if err != nil {
		log.Fatalf("connect %s: %v", natsURL, err)
	}
	defer nc.Close()

	js, err := jetstream.New(nc, jetstream.WithPublishAsyncMaxPending(maxPending))
	if err != nil {
		log.Fatalf("jetstream: %v", err)
	}

	// distribute round-robin over BENCH1..BENCHn, same scheme as seed_sql()/seedsqs
	perQueue := make([]int, nqueues)
	for n := 1; n <= total; n++ {
		perQueue[(n-1)%nqueues]++
	}

	futures := make([]jetstream.PubAckFuture, 0, total)
	var submitErrs int64
	id := 0
	for qi := 1; qi <= nqueues; qi++ {
		subject := fmt.Sprintf("bench.%d.BENCH", qi)
		for i := 0; i < perQueue[qi-1]; i++ {
			id++
			body := fmt.Sprintf(
				`{"id":"bench-%d","poolCode":"BENCH","mediationType":"HTTP","mediationTarget":"%s","dispatchMode":"IMMEDIATE"}`,
				id, target)
			f, err := js.PublishAsync(subject, []byte(body))
			if err != nil {
				submitErrs++
				log.Printf("PublishAsync submit error subject=%s: %v", subject, err)
				continue
			}
			futures = append(futures, f)
		}
	}

	select {
	case <-js.PublishAsyncComplete():
	case <-time.After(120 * time.Second):
		log.Fatalf("timed out waiting for %d outstanding publishes to complete", len(futures))
	}

	var acked, failed int64
	for _, f := range futures {
		select {
		case <-f.Ok():
			acked++
		case err := <-f.Err():
			failed++
			log.Printf("publish ack error subject=%s: %v", f.Msg().Subject, err)
		}
	}

	fmt.Printf("seednats: acked=%d failed=%d submit_errors=%d total=%d queues=%d\n",
		acked, failed, submitErrs, total, nqueues)
	if failed > 0 || submitErrs > 0 {
		os.Exit(1)
	}
}
