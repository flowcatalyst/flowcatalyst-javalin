// seedsqs: a one-shot bulk producer that fills TOTAL messages across QUEUES SQS queues on
// LocalStack, before the router container ever starts — the SQS equivalent of run.sh's
// seed_sql() for the Postgres broker (bench/router/RESULTS.md "Measuring the drain"). Body
// shape matches the Postgres rows exactly (docs/spec/router.md §2.1, §7.2 "Publish sends
// JSON(Message)"): {"id":...,"poolCode":"BENCH","mediationType":"HTTP","mediationTarget":...,
// "dispatchMode":"IMMEDIATE"}. Batches of 10 (SendMessageBatch's hard limit), fired
// concurrently, one queue per batch (SQS requires every entry in a batch to share a queue).
package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awscfg "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/sqs/types"
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

type job struct {
	queueURL string
	poolCode string
	ids      []int
}

func main() {
	total := envInt("TOTAL", 50000)
	nqueues := envInt("QUEUES", 1)
	endpoint := os.Getenv("ENDPOINT") // e.g. http://172.30.0.12:4566
	region := envStr("REGION", "us-east-1")
	accountID := envStr("ACCOUNT_ID", "000000000000")
	target := os.Getenv("MEDIATION_TARGET") // e.g. http://172.30.0.11:9000/hook
	concurrency := envInt("CONCURRENCY", 64)
	// POOLS: 1 = poolCode "BENCH" on every message; N>1 = queue q (1-based) carries
	// "BENCH-(((q-1)%N)+1)" (same mapping as the sink's /config and run.sh's seed_sql).
	pools := envInt("POOLS", 1)
	// ID_OFFSET shifts the numbering of the "id" in each body, so several invocations against
	// the same router (a slow warm-up fed in chunks, then the main backlog) never repeat an id
	// the router's in-flight tracker could still hold.
	idOffset := envInt("ID_OFFSET", 0)

	if endpoint == "" || target == "" {
		log.Fatal("ENDPOINT and MEDIATION_TARGET are required")
	}

	ctx := context.Background()
	cfg, err := awscfg.LoadDefaultConfig(ctx,
		awscfg.WithRegion(region),
		awscfg.WithCredentialsProvider(credentials.NewStaticCredentialsProvider("test", "test", "")),
	)
	if err != nil {
		log.Fatalf("load config: %v", err)
	}
	client := sqs.NewFromConfig(cfg, func(o *sqs.Options) {
		o.BaseEndpoint = aws.String(endpoint) // our own tool: explicit, not testing env-var honouring
	})

	// The same "real AWS-shaped" URL the router's config uses (docs/spec/router.md §7.1:
	// scheme resolves to sqs only when the host starts with sqs. and contains .amazonaws.) —
	// LocalStack accepts any host in the QueueUrl parameter and resolves by path (verified:
	// a message sent/received against this exact URL shape round-tripped through LocalStack
	// with only AWS_ENDPOINT_URL_SQS pointing requests at it).
	queueURLs := make([]string, nqueues)
	for i := 0; i < nqueues; i++ {
		queueURLs[i] = fmt.Sprintf("https://sqs.%s.amazonaws.com/%s/BENCH-%d", region, accountID, i+1)
	}

	perQueue := make([][]int, nqueues)
	for n := 1; n <= total; n++ {
		qi := (n - 1) % nqueues
		perQueue[qi] = append(perQueue[qi], n)
	}

	jobs := make(chan job, 256)
	var sent, failed int64
	var wg sync.WaitGroup
	for w := 0; w < concurrency; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := range jobs {
				entries := make([]types.SendMessageBatchRequestEntry, len(j.ids))
				for k, id := range j.ids {
					body := fmt.Sprintf(
						`{"id":"bench-%d","poolCode":"%s","mediationType":"HTTP","mediationTarget":"%s","dispatchMode":"IMMEDIATE"}`,
						id+idOffset, j.poolCode, target)
					entries[k] = types.SendMessageBatchRequestEntry{
						Id:          aws.String(strconv.Itoa(id)),
						MessageBody: aws.String(body),
					}
				}
				out, err := client.SendMessageBatch(ctx, &sqs.SendMessageBatchInput{
					QueueUrl: aws.String(j.queueURL),
					Entries:  entries,
				})
				if err != nil {
					log.Printf("batch error queue=%s: %v", j.queueURL, err)
					atomic.AddInt64(&failed, int64(len(j.ids)))
					continue
				}
				atomic.AddInt64(&sent, int64(len(out.Successful)))
				atomic.AddInt64(&failed, int64(len(out.Failed)))
				for _, f := range out.Failed {
					log.Printf("entry failed queue=%s id=%s code=%s msg=%s", j.queueURL, aws.ToString(f.Id), aws.ToString(f.Code), aws.ToString(f.Message))
				}
			}
		}()
	}

	poolCodeFor := func(qi int) string {
		if pools > 1 {
			return fmt.Sprintf("BENCH-%d", (qi%pools)+1) // qi is 0-based here
		}
		return "BENCH"
	}
	if rate := float64(envInt("RATE", 0)); rate > 0 {
		// RATE=N messages/second: spread the sends over time, round-robin across the queues (a
		// real producer feeds every queue at once, not one queue at a time), pacing against an
		// absolute schedule so a slow send is caught up rather than slowing the whole run.
		maxLen := 0
		for _, ids := range perQueue {
			if len(ids) > maxLen {
				maxLen = len(ids)
			}
		}
		t0 := time.Now()
		issued := 0
		for i := 0; i < maxLen; i += 10 {
			for qi, ids := range perQueue {
				if i >= len(ids) {
					continue
				}
				end := min(i+10, len(ids))
				if d := time.Until(t0.Add(time.Duration(float64(issued) / rate * float64(time.Second)))); d > 0 {
					time.Sleep(d)
				}
				jobs <- job{queueURL: queueURLs[qi], poolCode: poolCodeFor(qi), ids: ids[i:end]}
				issued += end - i
			}
		}
	} else {
		for qi, ids := range perQueue {
			for i := 0; i < len(ids); i += 10 {
				end := min(i+10, len(ids))
				jobs <- job{queueURL: queueURLs[qi], poolCode: poolCodeFor(qi), ids: ids[i:end]}
			}
		}
	}
	close(jobs)
	wg.Wait()

	fmt.Printf("seedsqs: sent=%d failed=%d total=%d queues=%d\n", sent, failed, total, nqueues)
	if failed > 0 {
		os.Exit(1)
	}
}
