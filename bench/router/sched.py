#!/usr/bin/env python3
"""Sampler + report for bench/router/sched.sh (dispatch job scheduler publish throughput).

  sched.py sample <out.jsonl> <pg-container> <db> <sqsfix-container> <n> <timeout-s> <interval-s>
      Once per interval: sqsfix /stats (sent messages, SendMessageBatch calls) and
      `SELECT status, count(*)` over msg_dispatch_jobs for the benchmark rows. Stops when no
      benchmark row is PENDING, or at the timeout. Runs on the host; nothing runs inside the
      server container.
  sched.py report <out.jsonl> <n> <base-sent> <base-calls> <final-sent> <final-calls> <final-pending>
      Prints the summary lines. base-* are the sqsfix counters just before the seed (the
      readiness warm-up is in them); final-* the counters and the PENDING count read as a pair
      after the settle pause with the server frozen.
"""
import json
import subprocess
import sys
import time


def sh(args):
    return subprocess.run(args, capture_output=True, text=True, timeout=20).stdout


def stats(sqsfix):
    try:
        j = json.loads(sh(["docker", "exec", sqsfix, "wget", "-qO-", "http://127.0.0.1:4566/stats"]))
        return j.get("sent", 0), j.get("calls", {}).get("SendMessageBatch", 0), j.get("calls", {}).get("SendMessage", 0)
    except Exception:
        return None


def counts(pg, db):
    out = sh(["docker", "exec", pg, "psql", "-U", "pg", "-d", db, "-tAc",
              "SELECT status, count(*) FROM msg_dispatch_jobs WHERE id LIKE 'B%' GROUP BY status"])
    c = {}
    for line in out.splitlines():
        if "|" in line:
            s, n = line.split("|")
            c[s] = int(n)
    return c


def sample(path, pg, db, sqsfix, n, timeout_s, interval_s):
    t0 = time.time()
    with open(path, "w") as f:
        tick = 0
        while True:
            now = time.time()
            st = stats(sqsfix)
            try:
                c = counts(pg, db)
            except Exception:
                c = None
            if st is not None and c:
                row = {"t": round(now - t0, 3), "sent": st[0], "batch_calls": st[1], "single_calls": st[2],
                       "pending": c.get("PENDING", 0), "status": c}
                f.write(json.dumps(row) + "\n")
                f.flush()
                if row["pending"] == 0 and sum(c.values()) >= n:
                    return
            if now - t0 >= timeout_s:
                return
            tick += 1
            time.sleep(max(0.0, t0 + tick * interval_s - time.time()))


def cross(rows, n, frac):
    """Time at which published (= n - PENDING) first reaches frac*n, linearly interpolated."""
    target = frac * n
    prev_t, prev_d = 0.0, 0
    for r in rows:
        d = n - r["pending"]
        if d >= target:
            if d == prev_d:
                return r["t"]
            return prev_t + (r["t"] - prev_t) * (target - prev_d) / (d - prev_d)
        prev_t, prev_d = r["t"], d
    return None


def report(path, n, base_sent, base_calls, final_sent, final_calls, final_pending):
    rows = [json.loads(line) for line in open(path) if line.strip()]
    if not rows:
        print("   NO SAMPLES")
        return
    last = rows[-1]
    done = n - last["pending"]
    final_done = n - final_pending
    drained = last["pending"] == 0
    elapsed = last["t"]
    # First publish seen: the poller sleeps up to one poll interval before it notices the seed.
    first = next((r["t"] for r in rows if n - r["pending"] > 0), None)
    overall = done / elapsed if elapsed > 0 else 0.0
    t10, t90 = cross(rows, n, 0.10), cross(rows, n, 0.90)
    if t10 is not None and t90 is not None and t90 > t10:
        steady = f"{0.8 * n / (t90 - t10):.0f}"
        steady_note = f"10-90% of N, t10={t10:.1f}s t90={t90:.1f}s"
    else:
        # Did not reach 90% inside the window: the honest figure is the rate over what it did
        # do, measured from the first publish to the last sample.
        span = elapsed - (first or 0.0)
        steady = f"{done / span:.0f}" if span > 0 and done else "0"
        steady_note = "did NOT reach 90% of N; rate over the measured window from the first publish"
    sent = final_sent - base_sent
    calls = final_calls - base_calls
    per = sent / calls if calls else 0.0
    print(f"   total_jobs={n} published={done} pending_left={last['pending']} "
          + (f"drain_s={elapsed:.1f}" if drained else f"TIMEOUT_after_s={elapsed:.1f}")
          + f" first_publish_seen_s={first if first is not None else 'never'}")
    print(f"   jobs_per_s_overall={overall:.0f} jobs_per_s_steady={steady} ({steady_note})")
    print(f"   SendMessageBatch_calls={calls} sqs_messages_sent={sent} entries_per_call={per:.2f}")
    # sent and final_done were read as a pair with the server frozen (sched.sh).
    diff = sent - final_done
    if diff == 0:
        verdict = f"sent == published ({sent})"
    elif not drained and 0 < diff <= 100:
        verdict = (f"sent {sent} vs published {final_done}: {diff} apart, which is within the one claim "
                   "published but not yet committed when the still-running server was frozen (not a duplicate)")
    elif diff > 0:
        verdict = f"DUPLICATES: {diff} more messages on the queue ({sent}) than jobs marked published ({final_done})"
    else:
        verdict = f"SHORTFALL: {-diff} jobs marked published ({final_done}) with no queue message ({sent} sent)"
    full = "" if drained else f" (N={n} not reached: {final_pending} still PENDING at the end)"
    print(f"   correctness: {verdict}{full}; status counts at the last sample {last['status']}")


if __name__ == "__main__":
    if sys.argv[1] == "sample":
        sample(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5], int(sys.argv[6]), float(sys.argv[7]), float(sys.argv[8]))
    elif sys.argv[1] == "report":
        report(sys.argv[2], *[int(a) for a in sys.argv[3:9]])
    else:
        sys.exit("usage: sched.py sample|report ...")
