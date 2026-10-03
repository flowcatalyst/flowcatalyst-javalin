// sqsfix: a minimal in-memory SQS server speaking only the AWS JSON 1.0 protocol
// (X-Amz-Target: AmazonSQS.<Op>), which current AWS SDKs use for SQS. It exists so router
// throughput benchmarks measure the router, not an emulator: no XML, no persistence, no
// auth checks, no per-message attribute MD5, queues are created lazily on first reference.
//
// Supported: CreateQueue GetQueueUrl GetQueueAttributes SendMessage SendMessageBatch
// ReceiveMessage (long poll, visibility timeout) DeleteMessage DeleteMessageBatch
// ChangeMessageVisibility ChangeMessageVisibilityBatch PurgeQueue. GET /stats for counters.
package main

import (
	"container/heap"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type msg struct {
	id         string
	body       string
	md5        string
	sentMs     int64
	firstRecv  int64
	recvCount  int
	gen        int // bumped on every receive / visibility change; invalidates stale heap entries
	inflight   bool
	deadlineNs int64
}

type hent struct {
	deadlineNs int64
	m          *msg
	gen        int
}
type hp []hent

func (h hp) Len() int           { return len(h) }
func (h hp) Less(i, j int) bool { return h[i].deadlineNs < h[j].deadlineNs }
func (h hp) Swap(i, j int)      { h[i], h[j] = h[j], h[i] }
func (h *hp) Push(x any)        { *h = append(*h, x.(hent)) }
func (h *hp) Pop() any {
	o := *h
	n := len(o)
	x := o[n-1]
	*h = o[:n-1]
	return x
}

type queue struct {
	name string
	mu   sync.Mutex
	vt   int
	// ready is a FIFO ring: ready[head:] are visible messages.
	ready    []*msg
	head     int
	inflight map[string]*msg
	timers   hp
	wake     chan struct{} // closed+replaced when messages become visible
}

func newQueue(name string, vt int) *queue {
	return &queue{name: name, vt: vt, inflight: map[string]*msg{}, wake: make(chan struct{})}
}

func (q *queue) pushReady(m *msg) {
	m.inflight = false
	q.ready = append(q.ready, m)
}

// signal wakes long pollers; caller holds q.mu.
func (q *queue) signal() {
	close(q.wake)
	q.wake = make(chan struct{})
}

func (q *queue) readyLen() int { return len(q.ready) - q.head }

func (q *queue) compact() {
	if q.head > 4096 && q.head*2 > len(q.ready) {
		q.ready = append([]*msg(nil), q.ready[q.head:]...)
		q.head = 0
	}
}

// reap moves expired in-flight messages back to ready; caller holds q.mu.
func (q *queue) reap(nowNs int64) {
	moved := false
	for len(q.timers) > 0 && q.timers[0].deadlineNs <= nowNs {
		e := heap.Pop(&q.timers).(hent)
		if e.m.inflight && e.m.gen == e.gen {
			delete(q.inflight, e.m.id)
			q.pushReady(e.m)
			moved = true
		}
	}
	if moved {
		q.signal()
	}
}

type server struct {
	mu     sync.RWMutex
	queues map[string]*queue
	defVT  int
	seq    atomic.Uint64

	sent, received, deleted, changed, empty atomic.Int64

	// calls counts HTTP requests per operation, so a run can report messages per call
	// (how well receive/delete/visibility batching is actually doing).
	calls sync.Map // op -> *atomic.Int64
}

func (s *server) countCall(op string) {
	v, ok := s.calls.Load(op)
	if !ok {
		v, _ = s.calls.LoadOrStore(op, new(atomic.Int64))
	}
	v.(*atomic.Int64).Add(1)
}

func (s *server) queue(name string) *queue {
	s.mu.RLock()
	q := s.queues[name]
	s.mu.RUnlock()
	if q != nil {
		return q
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if q = s.queues[name]; q == nil {
		q = newQueue(name, s.defVT)
		s.queues[name] = q
	}
	return q
}

func queueName(url string) string {
	url = strings.TrimRight(url, "/")
	return url[strings.LastIndexByte(url, '/')+1:]
}

func (s *server) newID() string {
	n := s.seq.Add(1)
	return fmt.Sprintf("%08x-0000-4000-8000-%012x", uint32(n>>32)+0xfc, n)
}

func sum(b string) string { h := md5.Sum([]byte(b)); return hex.EncodeToString(h[:]) }

type apiErr struct{ typ, msg string }

func (e apiErr) Error() string { return e.typ + ": " + e.msg }

func (s *server) send(q *queue, body string) (string, string) {
	m := &msg{id: s.newID(), body: body, md5: sum(body), sentMs: time.Now().UnixMilli()}
	q.mu.Lock()
	q.pushReady(m)
	q.signal()
	q.mu.Unlock()
	s.sent.Add(1)
	return m.id, m.md5
}

func handleOf(m *msg) string { return fmt.Sprintf("%s#%d", m.id, m.recvCount) }

// take pops up to max visible messages into flight; caller holds q.mu.
func (s *server) take(q *queue, max, vt int) []*msg {
	n := q.readyLen()
	if n > max {
		n = max
	}
	if n == 0 {
		return nil
	}
	now := time.Now()
	dl := now.Add(time.Duration(vt) * time.Second).UnixNano()
	out := make([]*msg, n)
	for i := 0; i < n; i++ {
		m := q.ready[q.head]
		q.ready[q.head] = nil
		q.head++
		m.inflight, m.recvCount, m.deadlineNs = true, m.recvCount+1, dl
		m.gen++
		if m.firstRecv == 0 {
			m.firstRecv = now.UnixMilli()
		}
		q.inflight[m.id] = m
		heap.Push(&q.timers, hent{dl, m, m.gen})
		out[i] = m
	}
	q.compact()
	return out
}

func handleID(h string) string {
	if i := strings.IndexByte(h, '#'); i >= 0 {
		return h[:i]
	}
	return h
}

func (s *server) del(q *queue, handle string) {
	id := handleID(handle)
	q.mu.Lock()
	if m, ok := q.inflight[id]; ok && handleOf(m) == handle {
		delete(q.inflight, id)
		m.inflight = false
		m.gen++
		s.deleted.Add(1)
	}
	q.mu.Unlock()
}

func (s *server) changeVis(q *queue, handle string, vt int) {
	id := handleID(handle)
	q.mu.Lock()
	if m, ok := q.inflight[id]; ok && handleOf(m) == handle {
		m.gen++
		if vt <= 0 {
			delete(q.inflight, id)
			q.pushReady(m)
			q.signal()
		} else {
			m.deadlineNs = time.Now().Add(time.Duration(vt) * time.Second).UnixNano()
			heap.Push(&q.timers, hent{m.deadlineNs, m, m.gen})
		}
		s.changed.Add(1)
	}
	q.mu.Unlock()
}

type m_ = map[string]any

func (s *server) dispatch(r *http.Request, op string, body []byte) (any, error) {
	host := r.Host
	switch op {
	case "CreateQueue", "GetQueueUrl":
		var in struct {
			QueueName  string
			Attributes map[string]string
		}
		if err := json.Unmarshal(body, &in); err != nil {
			return nil, err
		}
		q := s.queue(in.QueueName)
		if v, ok := in.Attributes["VisibilityTimeout"]; ok {
			fmt.Sscanf(v, "%d", &q.vt)
		}
		return m_{"QueueUrl": "http://" + host + "/000000000000/" + in.QueueName}, nil
	case "SendMessage":
		var in struct{ QueueUrl, MessageBody string }
		if err := json.Unmarshal(body, &in); err != nil {
			return nil, err
		}
		id, md := s.send(s.queue(queueName(in.QueueUrl)), in.MessageBody)
		return m_{"MessageId": id, "MD5OfMessageBody": md}, nil
	case "SendMessageBatch":
		var in struct {
			QueueUrl string
			Entries  []struct{ Id, MessageBody string }
		}
		if err := json.Unmarshal(body, &in); err != nil {
			return nil, err
		}
		q := s.queue(queueName(in.QueueUrl))
		ok := make([]m_, 0, len(in.Entries))
		for _, e := range in.Entries {
			id, md := s.send(q, e.MessageBody)
			ok = append(ok, m_{"Id": e.Id, "MessageId": id, "MD5OfMessageBody": md})
		}
		return m_{"Successful": ok, "Failed": []m_{}}, nil
	case "ReceiveMessage":
		var in struct {
			QueueUrl            string
			MaxNumberOfMessages int
			VisibilityTimeout   *int
			WaitTimeSeconds     int
		}
		if err := json.Unmarshal(body, &in); err != nil {
			return nil, err
		}
		if in.MaxNumberOfMessages <= 0 {
			in.MaxNumberOfMessages = 1
		}
		q := s.queue(queueName(in.QueueUrl))
		deadline := time.Now().Add(time.Duration(in.WaitTimeSeconds) * time.Second)
		for {
			q.mu.Lock()
			q.reap(time.Now().UnixNano())
			vt := q.vt
			if in.VisibilityTimeout != nil {
				vt = *in.VisibilityTimeout
			}
			got := s.take(q, in.MaxNumberOfMessages, vt)
			wake := q.wake
			q.mu.Unlock()
			if len(got) > 0 {
				out := make([]m_, len(got))
				for i, m := range got {
					out[i] = m_{
						"MessageId": m.id, "ReceiptHandle": handleOf(m), "MD5OfBody": m.md5, "Body": m.body,
						"Attributes": map[string]string{
							"ApproximateReceiveCount":          fmt.Sprint(m.recvCount),
							"SentTimestamp":                    fmt.Sprint(m.sentMs),
							"ApproximateFirstReceiveTimestamp": fmt.Sprint(m.firstRecv),
							"SenderId":                         "000000000000",
						},
					}
				}
				s.received.Add(int64(len(got)))
				return m_{"Messages": out}, nil
			}
			rem := time.Until(deadline)
			if rem <= 0 {
				s.empty.Add(1)
				return m_{}, nil
			}
			t := time.NewTimer(min(rem, time.Second)) // also re-reap expired visibility
			select {
			case <-wake:
			case <-t.C:
			case <-r.Context().Done():
				t.Stop()
				return m_{}, nil
			}
			t.Stop()
		}
	case "DeleteMessage":
		var in struct{ QueueUrl, ReceiptHandle string }
		if err := json.Unmarshal(body, &in); err != nil {
			return nil, err
		}
		s.del(s.queue(queueName(in.QueueUrl)), in.ReceiptHandle)
		return m_{}, nil
	case "DeleteMessageBatch":
		var in struct {
			QueueUrl string
			Entries  []struct{ Id, ReceiptHandle string }
		}
		if err := json.Unmarshal(body, &in); err != nil {
			return nil, err
		}
		q := s.queue(queueName(in.QueueUrl))
		ok := make([]m_, 0, len(in.Entries))
		for _, e := range in.Entries {
			s.del(q, e.ReceiptHandle)
			ok = append(ok, m_{"Id": e.Id})
		}
		return m_{"Successful": ok, "Failed": []m_{}}, nil
	case "ChangeMessageVisibility":
		var in struct {
			QueueUrl, ReceiptHandle string
			VisibilityTimeout       int
		}
		if err := json.Unmarshal(body, &in); err != nil {
			return nil, err
		}
		s.changeVis(s.queue(queueName(in.QueueUrl)), in.ReceiptHandle, in.VisibilityTimeout)
		return m_{}, nil
	case "ChangeMessageVisibilityBatch":
		var in struct {
			QueueUrl string
			Entries  []struct {
				Id, ReceiptHandle string
				VisibilityTimeout int
			}
		}
		if err := json.Unmarshal(body, &in); err != nil {
			return nil, err
		}
		q := s.queue(queueName(in.QueueUrl))
		ok := make([]m_, 0, len(in.Entries))
		for _, e := range in.Entries {
			s.changeVis(q, e.ReceiptHandle, e.VisibilityTimeout)
			ok = append(ok, m_{"Id": e.Id})
		}
		return m_{"Successful": ok, "Failed": []m_{}}, nil
	case "GetQueueAttributes":
		var in struct{ QueueUrl string }
		if err := json.Unmarshal(body, &in); err != nil {
			return nil, err
		}
		q := s.queue(queueName(in.QueueUrl))
		q.mu.Lock()
		q.reap(time.Now().UnixNano())
		vis, fl, vt := q.readyLen(), len(q.inflight), q.vt
		q.mu.Unlock()
		return m_{"Attributes": map[string]string{
			"ApproximateNumberOfMessages":           fmt.Sprint(vis),
			"ApproximateNumberOfMessagesNotVisible": fmt.Sprint(fl),
			"ApproximateNumberOfMessagesDelayed":    "0",
			"VisibilityTimeout":                     fmt.Sprint(vt),
		}}, nil
	case "PurgeQueue":
		var in struct{ QueueUrl string }
		if err := json.Unmarshal(body, &in); err != nil {
			return nil, err
		}
		q := s.queue(queueName(in.QueueUrl))
		q.mu.Lock()
		q.ready, q.head, q.inflight, q.timers = nil, 0, map[string]*msg{}, nil
		q.mu.Unlock()
		return m_{}, nil
	}
	return nil, apiErr{"com.amazon.coral.service#UnknownOperationException", "unsupported operation " + op}
}

func (s *server) stats(w http.ResponseWriter) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var ready, fl int
	for _, q := range s.queues {
		q.mu.Lock()
		ready += q.readyLen()
		fl += len(q.inflight)
		q.mu.Unlock()
	}
	w.Header().Set("Content-Type", "application/json")
	calls := m_{}
	s.calls.Range(func(k, v any) bool { calls[k.(string)] = v.(*atomic.Int64).Load(); return true })
	json.NewEncoder(w).Encode(m_{
		"calls":  calls,
		"queues": len(s.queues), "ready": ready, "inflight": fl, "depth": ready + fl,
		"sent": s.sent.Load(), "received": s.received.Load(), "deleted": s.deleted.Load(),
		"visibilityChanges": s.changed.Load(), "emptyReceives": s.empty.Load(),
	})
}

func main() {
	addr := flag.String("addr", ":4566", "listen address")
	vt := flag.Int("vt", 120, "default visibility timeout seconds for lazily created queues")
	verbose := flag.Bool("v", false, "log the first request of each operation (protocol check)")
	flag.Parse()
	s := &server{queues: map[string]*queue{}, defVT: *vt}

	go func() { // expire visibility for queues nobody is polling
		for range time.Tick(500 * time.Millisecond) {
			now := time.Now().UnixNano()
			s.mu.RLock()
			for _, q := range s.queues {
				q.mu.Lock()
				q.reap(now)
				q.mu.Unlock()
			}
			s.mu.RUnlock()
		}
	}()

	var seen sync.Map
	http.HandleFunc("/stats", func(w http.ResponseWriter, r *http.Request) { s.stats(w) })
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		target := r.Header.Get("X-Amz-Target")
		op := target[strings.LastIndexByte(target, '.')+1:]
		if *verbose {
			if _, dup := seen.LoadOrStore(op+r.UserAgent(), 1); !dup {
				log.Printf("first %s %s %s ct=%q ua=%q", r.Method, r.URL.Path, target, r.Header.Get("Content-Type"), r.UserAgent())
			}
		}
		if op == "" {
			http.Error(w, "sqsfix speaks only the AWS JSON protocol (X-Amz-Target missing)", 400)
			return
		}
		var buf strings.Builder
		if _, err := fmt_copy(&buf, r); err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		s.countCall(op)
		out, err := s.dispatch(r, op, []byte(buf.String()))
		w.Header().Set("Content-Type", "application/x-amz-json-1.0")
		if err != nil {
			typ := "com.amazon.coral.service#SerializationException"
			if ae, ok := err.(apiErr); ok {
				typ = ae.typ
			}
			w.WriteHeader(400)
			json.NewEncoder(w).Encode(m_{"__type": typ, "message": err.Error()})
			return
		}
		json.NewEncoder(w).Encode(out)
	})
	log.Printf("sqsfix listening on %s (default visibility timeout %ds)", *addr, *vt)
	log.Fatal((&http.Server{Addr: *addr}).ListenAndServe())
}
