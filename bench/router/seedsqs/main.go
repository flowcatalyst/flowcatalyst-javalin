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
						`{"id":"bench-%d","poolCode":"BENCH","mediationType":"HTTP","mediationTarget":"%s","dispatchMode":"IMMEDIATE"}`,
						id, target)
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

	for qi, ids := range perQueue {
		for i := 0; i < len(ids); i += 10 {
			end := i + 10
			if end > len(ids) {
				end = len(ids)
			}
			jobs <- job{queueURL: queueURLs[qi], ids: ids[i:end]}
		}
	}
	close(jobs)
	wg.Wait()

	fmt.Printf("seedsqs: sent=%d failed=%d total=%d queues=%d\n", sent, failed, total, nqueues)
	if failed > 0 {
		os.Exit(1)
	}
}
