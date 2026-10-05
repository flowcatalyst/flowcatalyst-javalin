#!/usr/bin/env python3
"""Rate "while being fed" for a run.sh warm-up + fed main phase (BROKER=sqs SQS_EMULATOR=sqsfix).

  fedrate.py sample <out.jsonl> [interval-s]     run in the background for the whole run.sh run
  fedrate.py report <out.jsonl> <warmup> <main> [server.log] [metrics.prom]

sample: every interval (default 0.5 s) one `docker exec bench-router-prober` that reads the
sink's /stats (count = deliveries received by the sink) and sqsfix's /stats (sent = messages
the seeder has put on the queues, received = messages handed to the router, deleted = acked).
report: the FED WINDOW is from the first sample with sent > warmup to the last sample with
sent < warmup + main (the seeder is still feeding at both ends). delivered/s and received/s are
the slopes of the sink count and of sqsfix received over that window; held = received -
delivered at the end of the window (messages inside the router); complete_s = first sample
with sink count >= warmup + main, minus the start of the window. With a server log holding
GODEBUG=gctrace=1 lines, the last line's cumulative "N%" (GC CPU as a share of the process's
available CPU time since start, warm-up included) and the number of collections are printed.
With a metrics file, the sum of fc_queue_messages_total{outcome="deferred"}.
"""
import json, re, subprocess, sys, time

SINK = "http://172.30.0.11:9000/stats"
SQS = "http://172.30.0.12:4566/stats"


def sample(path, interval):
    with open(path, "w") as f:
        k = 0
        t0 = time.time()
        while True:
            try:
                out = subprocess.run(["docker", "exec", "bench-router-prober", "sh", "-c",
                                      f"curl -s -m 2 {SINK}; echo; curl -s -m 2 {SQS}"],
                                     capture_output=True, text=True, timeout=5).stdout.split("\n")
                out = [x for x in out if x.strip()]
                t = time.time()
                a, b = json.loads(out[0]), json.loads(out[1])
                f.write(json.dumps({"t": round(t, 3), "sink": a.get("count", 0), "sent": b.get("sent", 0),
                                    "received": b.get("received", 0), "deleted": b.get("deleted", 0)}) + "\n")
                f.flush()
            except Exception:
                pass
            k += 1
            time.sleep(max(0.0, t0 + k * interval - time.time()))


def report(path, warm, main, server_log=None, metrics=None):
    rows = [json.loads(l) for l in open(path) if l.strip()]
    win = [r for r in rows if warm < r["sent"] < warm + main]
    if len(win) < 2:
        print(f"   fed window: only {len(win)} samples (sent never seen strictly between {warm} and {warm + main})")
        return
    a, b = win[0], win[-1]
    dt = b["t"] - a["t"]
    done = next((r["t"] for r in rows if r["sink"] >= warm + main), None)
    line = (f"   fed window {dt:.1f}s ({len(win)} samples): fed_per_s={(b['sent'] - a['sent']) / dt:.0f} "
            f"delivered_per_s={(b['sink'] - a['sink']) / dt:.0f} received_per_s={(b['received'] - a['received']) / dt:.0f} "
            f"held_in_router_at_end={b['received'] - b['sink']} backlog_on_queues_at_end={b['sent'] - b['received']} "
            f"main_complete_s={'%.1f' % (done - a['t']) if done else 'not seen'}")
    print(line)
    if server_log:
        gc = None
        n = 0
        try:
            for l in open(server_log, errors="replace"):
                m = re.match(r"gc (\d+) @([\d.]+)s (\d+)%:", l)
                if m:
                    gc = (int(m.group(1)), float(m.group(2)), int(m.group(3)))
                    n += 1
        except OSError:
            pass
        print(f"   gctrace: {'no gc lines' if gc is None else f'{gc[0]} collections by {gc[1]:.0f}s of process life, cumulative GC CPU share {gc[2]}%'}")
    if metrics:
        tot = 0.0
        try:
            for l in open(metrics):
                if l.startswith("fc_queue_messages_total") and 'outcome="deferred"' in l:
                    tot += float(l.rsplit(" ", 1)[1])
            print(f"   deferrals (sum of fc_queue_messages_total outcome=deferred): {tot:.0f}")
        except OSError:
            print("   deferrals: no metrics file")


if __name__ == "__main__":
    if sys.argv[1] == "sample":
        sample(sys.argv[2], float(sys.argv[3]) if len(sys.argv) > 3 else 0.5)
    else:
        report(sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), *(sys.argv[5:7]))
