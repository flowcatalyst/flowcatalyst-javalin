#!/usr/bin/env python3
"""ts.py: 1-second sink-count time series + steady-rate / tail report for run.sh.

  ts.py sample <out.jsonl> <prober-container> <sink-stats-url> [interval_s]
      Runs until SIGTERM/SIGINT. Each interval it asks the sink's /stats (via `docker exec
      <prober> curl`, i.e. inside the existing prober container, never the router container)
      and appends {"t": seconds-since-start, "count": N}. Costs one curl per second.

  ts.py report <in.jsonl> <total> [pools]
      Prints the steady-rate / tail lines (steady rate between 10% and 90% of messages
      delivered, time to 90%, time to 100% or "timeout at X delivered", tail seconds 99% -> end,
      or the final stall rate when the run never completed). Add --rows to print per-second rows.
"""
import json, signal, subprocess, sys, time


def sample(out, prober, url, interval):
    stop = []
    signal.signal(signal.SIGTERM, lambda *a: stop.append(1))
    signal.signal(signal.SIGINT, lambda *a: stop.append(1))
    f = open(out, "w")
    t0 = time.time()
    n = 0
    while not stop:
        ts = time.time() - t0
        cnt = None
        try:
            r = subprocess.run(["docker", "exec", prober, "curl", "-s", "-m", "2", url],
                               capture_output=True, text=True, timeout=5)
            cnt = json.loads(r.stdout)["count"]
        except Exception:
            pass
        f.write(json.dumps({"t": round(ts, 2), "count": cnt}) + "\n")
        f.flush()
        n += 1
        nxt = t0 + n * interval
        while not stop and time.time() < nxt:
            time.sleep(min(0.05, max(0.0, nxt - time.time())))
    f.close()


def report(path, total, pools, rows):
    recs = []
    for line in open(path):
        line = line.strip()
        if line:
            try:
                recs.append(json.loads(line))
            except ValueError:
                pass
    recs = [r for r in recs if r.get("count") is not None]
    if not recs or total <= 0:
        print("   steady_rate: no samples")
        return
    if rows:
        prev = None
        for r in recs:
            rate = (r["count"] - prev["count"]) / max(r["t"] - prev["t"], 1e-9) if prev else 0
            print(f"   t={r['t']:7.1f} n={r['count']:8d} rate={rate:8.0f}/s")
            prev = r

    def first_at(frac):
        thr = frac * total
        for r in recs:
            if r["count"] >= thr:
                return r
        return None

    r10, r90, r99, r100 = first_at(0.10), first_at(0.90), first_at(0.99), first_at(1.0)
    last = recs[-1]
    steady = None
    if r10 and r90 and r90["t"] > r10["t"]:
        steady = (r90["count"] - r10["count"]) / (r90["t"] - r10["t"])
    t90 = f"{r90['t']:.1f}" if r90 else "n/a"
    if r100:
        t100 = f"{r100['t']:.1f}"
    else:
        t100 = f"timeout at {last['count']} delivered"
    # Tail: 99% -> end. If the run completed, seconds from 99% to 100%; otherwise the run is
    # stalled/slow, so report the seconds spent past 99% (if reached) and the final rate over
    # the last 10 s of samples.
    win = [r for r in recs if r["t"] >= last["t"] - 10]
    final_rate = (last["count"] - win[0]["count"]) / max(last["t"] - win[0]["t"], 1e-9) if len(win) > 1 else 0
    if r100 and r99:
        tail = f"{r100['t'] - r99['t']:.1f}"
    elif r99:
        tail = f"{last['t'] - r99['t']:.1f}+ (not finished; final_rate={final_rate:.0f}/s over last 10s)"
    else:
        tail = f"n/a (never reached 99%; final_rate={final_rate:.0f}/s over last 10s)"
    print(f"   steady_rate_10_90_per_s={round(steady) if steady is not None else 'n/a'} "
          f"time_to_90pct_s={t90} time_to_100pct_s={t100} tail_99_to_end_s={tail} pools={pools}")


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "sample":
        sample(sys.argv[2], sys.argv[3], sys.argv[4], float(sys.argv[5]) if len(sys.argv) > 5 else 1.0)
    elif len(sys.argv) >= 2 and sys.argv[1] == "report":
        args = [a for a in sys.argv[2:] if not a.startswith("--")]
        report(args[0], int(args[1]), args[2] if len(args) > 2 else "1", "--rows" in sys.argv)
    else:
        print(__doc__); sys.exit(2)
