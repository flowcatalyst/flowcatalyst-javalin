#!/usr/bin/env python3
"""Sampler + report for bench/router/sched.sh (dispatch job scheduler publish throughput).

  sched.py sample <out.jsonl> <pg-container> <db> <sqsfix-stats-url> <n> <timeout-s> <interval-s>
                  <base-sent> <sched.sh> <label>
      Every interval: sqsfix /stats over HTTP (sent messages, SendMessageBatch calls), minus
      the counters the warm-up left. The job table is NOT read while the scheduler is
      publishing: a count over the benchmark rows costs Postgres real time, and Postgres is
      part of what is measured. Once the queue holds n messages, `SELECT status, count(*)`
      every interval until no benchmark row is PENDING, or the timeout.
      Calls `<sched.sh> _snap <label> start` at the first message seen and `... end` when the
      queue holds n messages (cgroup CPU counters, and per-thread counters when THREADS=1).
  sched.py gate <out.jsonl> <metrics-url> <interval-s>
      Scrapes the server's Prometheus endpoint for fc_db_gate_* until killed.
  sched.py report <results-dir> <label> <n> <base-sent> <base-calls> <final-sent> <final-calls>
                  <final-pending> <t-seed-done> <srv> <pg> <sqsfix> <cpus> <statuses> <warns>
                  <errs> <single-calls> <warmup-dups> <meta>
      Prints the summary and writes <results-dir>/sched-<label>.json.
  sched.py threads <start.txt> <end.txt>
      The per-thread table alone.
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request


def sh(args):
    return subprocess.run(args, capture_output=True, text=True, timeout=60).stdout


def stats(url):
    try:
        with urllib.request.urlopen(url, timeout=5) as r:
            j = json.load(r)
        return j.get("sent", 0), j.get("calls", {}).get("SendMessageBatch", 0)
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


def sample(path, pg, db, url, n, timeout_s, interval_s, base_sent, sched_sh, label):
    t0 = time.time()
    snap_start = snap_end = None
    with open(path, "w") as f:
        tick = 0
        while True:
            now = time.time()
            st = stats(url)
            if st is not None:
                sent = st[0] - base_sent
                row = {"t": round(now, 3), "sent": sent, "batch_calls": st[1]}
                if sent > 0 and snap_start is None:
                    snap_start = subprocess.Popen([sched_sh, "_snap", label, "start"])
                if sent >= n:
                    if snap_end is None:
                        if snap_start is None:
                            snap_start = subprocess.Popen([sched_sh, "_snap", label, "start"])
                        snap_start.wait()
                        snap_end = subprocess.Popen([sched_sh, "_snap", label, "end"])
                    try:
                        c = counts(pg, db)
                    except Exception:
                        c = None
                    if c:
                        row["pending"] = c.get("PENDING", 0)
                        row["status"] = c
                f.write(json.dumps(row) + "\n")
                f.flush()
                if row.get("pending") == 0 and sum(row["status"].values()) >= n:
                    break
            if now - t0 >= timeout_s:
                if snap_end is None and snap_start is not None:
                    snap_start.wait()
                    snap_end = subprocess.Popen([sched_sh, "_snap", label, "end"])
                try:
                    c = counts(pg, db)
                    f.write(json.dumps({"t": round(time.time(), 3), "sent": (st[0] - base_sent) if st else -1,
                                        "batch_calls": st[1] if st else -1, "pending": c.get("PENDING", 0),
                                        "status": c, "timeout": True}) + "\n")
                except Exception:
                    pass
                break
            tick += 1
            time.sleep(max(0.0, t0 + tick * interval_s - time.time()))
    for p in (snap_start, snap_end):
        if p is not None:
            p.wait()


def gate(path, url, interval_s):
    with open(path, "w") as f:
        while True:
            t = time.time()
            try:
                with urllib.request.urlopen(url, timeout=3) as r:
                    text = r.read().decode("utf-8", "replace")
                m = {}
                for line in text.splitlines():
                    if line.startswith("fc_db_gate_") or line.startswith("fc_scheduler_buffer") or line.startswith("hikaricp_connections"):
                        k, _, v = line.rpartition(" ")
                        try:
                            m[k] = float(v)
                        except ValueError:
                            pass
                f.write(json.dumps({"t": round(t, 3), "m": m}) + "\n")
                f.flush()
            except Exception:
                pass
            time.sleep(max(0.0, t + interval_s - time.time()))


def cross(rows, n, frac):
    """Time at which sent first reaches frac*n, linearly interpolated between samples."""
    target = frac * n
    prev_t, prev_d = None, 0
    for r in rows:
        d = r["sent"]
        if d >= target:
            if prev_t is None or d == prev_d:
                return r["t"]
            return prev_t + (r["t"] - prev_t) * (target - prev_d) / (d - prev_d)
        prev_t, prev_d = r["t"], d
    return None


def read_cg(path):
    """/proc/uptime line followed by cgroup v2 cpu.stat."""
    try:
        lines = open(path).read().split("\n")
        d = {"uptime": float(lines[0].split()[0])}
        for line in lines[1:]:
            p = line.split()
            if len(p) == 2:
                d[p[0]] = int(p[1])
        return d if "usage_usec" in d else None
    except Exception:
        return None


def cg_cpu(out, label, container):
    a = read_cg(f"{out}/.sched-{label}.cg.{container}.start")
    b = read_cg(f"{out}/.sched-{label}.cg.{container}.end")
    if not a or not b or b["uptime"] <= a["uptime"]:
        return None
    w = b["uptime"] - a["uptime"]
    pct = lambda k: (b.get(k, 0) - a.get(k, 0)) / w / 1e4
    return {"window_s": round(w, 2), "cpu_pct": round(pct("usage_usec"), 1), "user_pct": round(pct("user_usec"), 1),
            "sys_pct": round(pct("system_usec"), 1),
            "throttled_ms": round((b.get("throttled_usec", 0) - a.get("throttled_usec", 0)) / 1e3),
            "nr_throttled": b.get("nr_throttled", 0) - a.get("nr_throttled", 0)}


def docker_stats(path, names, t_from, t_to):
    """Rounds of `docker stats --no-stream` that started inside [t_from, t_to - 1s]."""
    c = {n: [] for n in names}
    mem = 0.0
    unit = {"kib": 1 / 1024, "mib": 1, "gib": 1024}
    try:
        for line in open(path):
            p = line.split()
            if len(p) < 4 or p[1] not in c:
                continue
            try:
                ts, v = float(p[0]), float(p[2].rstrip("%"))
            except ValueError:
                continue
            if p[1] == names[0]:
                m = re.match(r"([\d.]+)([A-Za-z]+)", p[3])
                if m:
                    mem = max(mem, float(m.group(1)) * unit.get(m.group(2).lower(), 1))
            if t_from is not None and t_to is not None and t_from <= ts <= t_to - 1.0:
                c[p[1]].append(v)
    except OSError:
        pass
    return {n: {"mean": round(sum(v) / len(v)) if v else None, "max": round(max(v)) if v else None, "samples": len(v)}
            for n, v in c.items()}, round(mem)


def pgstat(path):
    """Classify pg_stat_statements rows: the claim, the mark-QUEUED update, the hold-back lookup."""
    res = {}
    try:
        lines = open(path).read().splitlines()
    except OSError:
        return res
    for line in lines:
        p = line.split("|", 8)
        if len(p) < 9:
            continue
        q = p[8]
        up = q.upper()
        kind = None
        if "GENERATE_SERIES" in up:
            continue  # the rig's own seed
        # The queue-table design (the claim is two statements on msg_dispatch_queue).
        if up.startswith("DELETE FROM MSG_DISPATCH_QUEUE") and "RETURNING" in up:
            kind = "claim_delete"
        elif (up.startswith("SELECT") and "FROM MSG_DISPATCH_QUEUE" in up and "ORDER BY" in up and "LIMIT" in up
              and "MSG_DISPATCH_JOBS" not in up and "LATERAL" not in up and "COUNT(" not in up):
            kind = "claim_select"
        elif up.startswith("WITH") and "UPDATE MSG_DISPATCH_JOBS" in up and "MSG_DISPATCH_QUEUE" in up :
            # leave-PENDING transitions: the job update and the queue delete in one statement;
            # the busiest one in the window is the mark-QUEUED update.
            kind = "mark"
        if kind is None and "MSG_DISPATCH_JOBS" not in up:
            continue
        # pg_stat_statements replaces literals with $n, so the statements are recognised by shape:
        # the busiest UPDATE of the job table in the window is the mark-QUEUED update.
        if kind is not None:
            pass
        elif "UPDATED_AT < $1" in up:
            continue  # stale recovery, not the mark-QUEUED update
        elif up.startswith("UPDATE MSG_DISPATCH_JOBS") or (up.startswith("WITH PK AS") and "UPDATE MSG_DISPATCH_JOBS" in up):
            # "WITH pk AS (... LATERAL primary-key lookup ...) UPDATE": Go's mark-QUEUED by primary key
            kind = "mark"
        elif "DISTINCT ON" in up:
            kind = "holdback"
        elif (up.startswith(("SELECT", "WITH")) and "SCHEDULED_FOR" in up and "COUNT(" not in up and "EXPLAIN" not in up
              and "MSG_DISPATCH_QUEUE" not in up):
            # The claim: the one SELECT of due PENDING rows (its text can be longer than the
            # 600 characters kept, so the ORDER BY is not relied on).
            kind = "claim"
        if kind is None or p[0] == "0":
            continue
        row = {"calls": int(p[0]), "total_ms": float(p[1]), "mean_ms": float(p[2]), "min_ms": float(p[3]),
               "max_ms": float(p[4]), "stddev_ms": float(p[5]), "rows": int(p[6]), "blks": int(p[7]), "query": q[:160]}
        # More than one text can match (e.g. a FOR UPDATE variant); keep the one with the most time.
        if kind not in res or row["total_ms"] > res[kind]["total_ms"]:
            res[kind] = row
    return res


def verify_order(path, n):
    """sqsfix /order: queue, MessageGroupId, entry Id, body id — one line per message, send order.

    Benchmark jobs are the ids starting with 'B'. Within one message group the seed's claim
    order (sequence, created_at, id) is ascending id, so a group's messages must arrive with
    ascending ids. A message whose group is its own id (or empty) is ungrouped: no order.
    """
    try:
        f = open(path)
    except OSError:
        return None
    seen = {}
    last = {}
    total = dups = violations = grouped = 0
    bad_groups = set()
    first_bad = None
    queues = set()
    for line in f:
        p = line.rstrip("\n").split("\t")
        if len(p) < 4:
            continue
        q, group, entry, bid = p
        jid = bid or entry
        if not jid.startswith("B"):
            continue
        total += 1
        queues.add(q)
        if jid in seen:
            dups += 1
        seen[jid] = seen.get(jid, 0) + 1
        if group and group != jid:
            grouped += 1
            key = (q, group)
            prev = last.get(key)
            if prev is not None and jid < prev:
                violations += 1
                bad_groups.add(key)
                if first_bad is None:
                    first_bad = f"group {group}: {jid} arrived after {prev}"
            if prev is None or jid > prev:
                last[key] = jid
    return {"messages": total, "unique_jobs": len(seen), "duplicates": dups, "missing": n - len(seen),
            "grouped_messages": grouped, "groups": len(last), "order_violations": violations,
            "groups_out_of_order": len(bad_groups), "first_violation": first_bad, "queues": len(queues)}


def read_threads(path):
    up = None
    proc = None
    th = {}
    try:
        lines = open(path).read().splitlines()
    except OSError:
        return None
    for line in lines:
        if line.startswith("UPTIME "):
            up = float(line.split()[1])
        elif line.startswith("PROC "):
            rest = line[line.rindex(")") + 2:].split()
            proc = (int(rest[11]), int(rest[12]))
        elif line.startswith("T|"):
            p = line.split("|")
            if len(p) < 5:
                continue
            tid, comm, stat, sw = p[1], p[2], "|".join(p[3:-1]), p[-1]
            # utime and stime are fields 14 and 15 of stat; counted after the last ')' because
            # the thread name (field 2) may contain spaces and parentheses.
            rest = stat[stat.rindex(")") + 2:].split()
            v = re.search(r"\bvoluntary_ctxt_switches:\s*(\d+)", sw)
            nv = re.search(r"nonvoluntary_ctxt_switches:\s*(\d+)", sw)
            th[tid] = (comm, int(rest[11]), int(rest[12]), int(v.group(1)) if v else 0, int(nv.group(1)) if nv else 0)
    if up is None:
        return None
    return {"uptime": up, "proc": proc, "threads": th}


def thread_table(start_path, end_path, top=40):
    a, b = read_threads(start_path), read_threads(end_path)
    if not a or not b or b["uptime"] <= a["uptime"]:
        return None
    w = b["uptime"] - a["uptime"]
    tick_ms = 1000.0 / 100  # CLK_TCK is 100 on Linux
    groups = {}
    for tid, (comm, ut, st, v, nv) in b["threads"].items():
        p = a["threads"].get(tid, (comm, 0, 0, 0, 0))
        name = re.sub(r"\d+", "N", comm)
        g = groups.setdefault(name, [0, 0.0, 0.0, 0, 0])
        g[0] += 1
        g[1] += (ut - p[1]) * tick_ms
        g[2] += (st - p[2]) * tick_ms
        g[3] += v - p[3]
        g[4] += nv - p[4]
    rows = sorted(groups.items(), key=lambda kv: -(kv[1][1] + kv[1][2]))
    tot_u = sum(g[1] for g in groups.values())
    tot_s = sum(g[2] for g in groups.values())
    out = {"window_s": round(w, 2), "threads_user_ms": round(tot_u), "threads_sys_ms": round(tot_s),
           "vol_per_s": round(sum(g[3] for g in groups.values()) / w), "invol_per_s": round(sum(g[4] for g in groups.values()) / w),
           "exited_threads": len(set(a["threads"]) - set(b["threads"])), "rows": []}
    if a["proc"] and b["proc"]:
        out["proc_user_ms"] = round((b["proc"][0] - a["proc"][0]) * tick_ms)
        out["proc_sys_ms"] = round((b["proc"][1] - a["proc"][1]) * tick_ms)
    for name, g in rows[:top]:
        out["rows"].append({"name": name, "threads": g[0], "user_ms": round(g[1]), "sys_ms": round(g[2]),
                            "vol_per_s": round(g[3] / w, 1), "invol_per_s": round(g[4] / w, 1)})
    return out


def print_threads(t):
    print(f"   threads of PID 1 over {t['window_s']}s (utime/stime from /proc/1/task/*/stat, 10 ms ticks; switches from status):")
    print(f"     all threads alive at the end: user {t['threads_user_ms']} ms, system {t['threads_sys_ms']} ms, "
          f"voluntary {t['vol_per_s']}/s, involuntary {t['invol_per_s']}/s"
          + (f"; process total (includes exited threads): user {t['proc_user_ms']} ms, system {t['proc_sys_ms']} ms"
             if "proc_user_ms" in t else "") + f"; threads that exited in the window: {t['exited_threads']}")
    print(f"     {'thread name (digits -> N)':<28} {'n':>3} {'user_ms':>8} {'sys_ms':>8} {'vol/s':>9} {'invol/s':>9}")
    for r in t["rows"]:
        if r["user_ms"] + r["sys_ms"] == 0 and r["vol_per_s"] < 1:
            continue
        print(f"     {r['name']:<28} {r['threads']:>3} {r['user_ms']:>8} {r['sys_ms']:>8} {r['vol_per_s']:>9} {r['invol_per_s']:>9}")


def report(out, label, n, base_sent, base_calls, final_sent, final_calls, final_pending, t_seed, srv, pg, sqsfix,
           cpus, statuses, warns, errs, singles, warm_dups, meta):
    rows = [json.loads(line) for line in open(f"{out}/sched-{label}.ts.jsonl") if line.strip()]
    res = {"label": label, "meta": meta, "n": n}
    if not rows:
        print("   NO SAMPLES")
        return
    last = rows[-1]
    drained = last.get("pending") == 0
    first = next((r["t"] for r in rows if r["sent"] > 0), None)
    t_all = next((r["t"] for r in rows if r["sent"] >= n), None)
    t10, t90 = cross(rows, n, 0.10), cross(rows, n, 0.90)
    if t10 is not None and t90 is not None and t90 > t10:
        steady = 0.8 * n / (t90 - t10)
        steady_note = f"10-90% of N, t10={t10 - t_seed:.2f}s t90={t90 - t_seed:.2f}s after the seed committed"
    else:
        span = last["t"] - (first or t_seed)
        steady = last["sent"] / span if span > 0 and last["sent"] > 0 else 0.0
        steady_note = "did NOT reach 90% of N; rate over the window from the first message"
    elapsed = last["t"] - t_seed
    sent = final_sent - base_sent
    calls = final_calls - base_calls
    per = sent / calls if calls else 0.0
    res.update({"steady_per_s": round(steady), "drain_s": round(elapsed, 2) if drained else None,
                "all_sent_s": round(t_all - t_seed, 2) if t_all else None,
                "first_publish_s": round(first - t_seed, 2) if first else None,
                "batch_calls": calls, "sent": sent, "entries_per_call": round(per, 2), "drained": drained})
    print(f"   total_jobs={n} messages_at_queue={sent} pending_left={final_pending} "
          + (f"drain_s={elapsed:.2f}" if drained else f"TIMEOUT_after_s={elapsed:.1f}")
          + f" all_messages_sent_s={res['all_sent_s']} first_message_seen_s={res['first_publish_s']}")
    print(f"   jobs_per_s_steady={steady:.0f} ({steady_note}; rate = messages arriving at sqsfix)")
    print(f"   SendMessageBatch_calls={calls} sqs_messages_sent={sent} entries_per_call={per:.2f}")
    # Messages that arrived in each whole second after the seed committed (sent interpolated
    # linearly between the two samples around each second boundary).
    nsec = int(os.environ.get("PERSEC", "30") or 0)
    if nsec > 0:
        def at(t):
            prev = (t_seed, 0)
            for r in rows:
                if r["t"] >= t:
                    if r["t"] == prev[0]:
                        return r["sent"]
                    return prev[1] + (r["sent"] - prev[1]) * (t - prev[0]) / (r["t"] - prev[0])
                if r["t"] > t_seed:
                    prev = (r["t"], r["sent"])
            return prev[1]
        per_s = []
        for k in range(nsec):
            if t_seed + k > last["t"]:
                break
            per_s.append(round(at(t_seed + k + 1) - at(t_seed + k)))
        res["per_second"] = per_s
        res["t_seed"] = t_seed
        print(f"   per_second_after_seed (first {nsec}s, messages arriving in second 1,2,...): " + " ".join(str(v) for v in per_s))

    # CPU.
    cg = {name: cg_cpu(out, label, c) for name, c in (("server", srv), ("postgres", pg), ("sqsfix", sqsfix))}
    ds, rss = docker_stats(f"{out}/.sched-{label}.cpu.tmp", [srv, pg, sqsfix], first, t_all)
    res["cpu"] = cg
    res["docker_stats"] = {"server": ds[srv], "postgres": ds[pg], "sqsfix": ds[sqsfix]}
    res["server_max_rss_mb"] = rss
    if all(cg.values()):
        s = cg["server"]
        print(f"   cpu over the publishing window ({s['window_s']}s, cgroup cpu.stat, 100 = one core; server limited to {cpus}): "
              + " ".join(f"{k}={v['cpu_pct']:.0f}% (user {v['user_pct']:.0f} sys {v['sys_pct']:.0f})" for k, v in cg.items())
              + f" server_throttled_ms={s['throttled_ms']}")
    else:
        print("   cpu: cgroup snapshots missing (the publishing window was never opened or closed)")
    print("   docker stats over the same window: "
          + " ".join(f"{k}_mean={v['mean']}% {k}_max={v['max']}%" for k, v in res["docker_stats"].items())
          + f" (samples={ds[srv]['samples']}) server_max_rss_mb={rss}")

    # pg_stat_statements.
    pgs = pgstat(f"{out}/sched-{label}.pgstat.txt")
    res["pg"] = pgs
    for kind in ("claim", "claim_select", "claim_delete", "mark", "holdback"):
        if kind in pgs:
            r = pgs[kind]
            print(f"   pg {kind}: calls={r['calls']} mean_ms={r['mean_ms']} max_ms={r['max_ms']} min_ms={r['min_ms']} "
                  f"rows_per_call={r['rows'] / r['calls']:.1f} blocks_per_call={r['blks'] / r['calls']:.0f} total_ms={r['total_ms']:.0f}")

    # Correctness.
    final_done = n - final_pending
    dup_by_count = sent - n
    order = verify_order(f"{out}/sched-{label}.order.tsv", n)
    res["order"] = order
    all_queued = statuses == f"QUEUED={n}"
    problems = []
    if sent != n:
        problems.append(f"messages {sent} != N {n} ({'duplicates ' + str(sent - n) if sent > n else 'SHORT by ' + str(n - sent)})")
    if not all_queued:
        problems.append(f"statuses {statuses} (expected QUEUED={n})")
    if final_pending:
        problems.append(f"{final_pending} PENDING")
    queue_left = os.environ.get("QUEUE_LEFT", "na")
    res["queue_rows_left"] = None if queue_left in ("", "na") else int(queue_left)
    if res["queue_rows_left"]:
        problems.append(f"{queue_left} rows left in msg_dispatch_queue")
    if errs:
        problems.append(f"{errs} error lines in the server log")
    if order is None:
        problems.append("order not checked (no /order dump)")
    else:
        if order["duplicates"] or order["missing"]:
            problems.append(f"queue holds {order['duplicates']} duplicate and lacks {order['missing']} jobs")
        if order["order_violations"]:
            problems.append(f"ORDER: {order['order_violations']} messages behind a later one of their group "
                            f"in {order['groups_out_of_order']} groups (first: {order['first_violation']})")
    res.update({"statuses": statuses, "warn_lines": warns, "error_lines": errs, "duplicates": dup_by_count,
                "warmup_duplicates": warm_dups, "single_calls": singles, "correct": not problems, "problems": problems})
    o = ("order not checked" if order is None else
         f"order: {order['grouped_messages']} grouped messages in {order['groups']} groups, "
         f"{order['order_violations']} out of order; queue has {order['unique_jobs']} distinct jobs, "
         f"{order['duplicates']} duplicates, {order['missing']} missing, {order['queues']} queue(s)")
    print(f"   correctness: {'OK' if not problems else 'PROBLEMS: ' + '; '.join(problems)}")
    print(f"     messages={sent} N={n} duplicates={dup_by_count} statuses={statuses} warn_lines={warns} error_lines={errs} "
          f"warmup_duplicates={warm_dups} queue_rows_left={queue_left}")
    print(f"     {o}")

    th = thread_table(f"{out}/sched-{label}.threads.start.txt", f"{out}/sched-{label}.threads.end.txt")
    if th:
        res["threads"] = th
        print_threads(th)

    gp = f"{out}/sched-{label}.gate.jsonl"
    if os.path.exists(gp):
        series = {}
        for line in open(gp):
            try:
                j = json.loads(line)
            except ValueError:
                continue
            if first is None or t_all is None or not (first <= j["t"] <= t_all):
                continue
            for k, v in j["m"].items():
                series.setdefault(k, []).append(v)
        res["gate"] = {k: {"mean": round(sum(v) / len(v), 2), "max": max(v), "samples": len(v)} for k, v in series.items()}
        print("   /metrics scraped once a second over the publishing window:")
        for k, v in sorted(res["gate"].items()):
            if "scheduler" in k or v["max"] > 0:
                print(f"     {k}: mean={v['mean']} max={v['max']} samples={v['samples']}")
        if not series:
            print("     (no fc_db_gate_* series found)")

    with open(f"{out}/sched-{label}.json", "w") as f:
        json.dump(res, f)


if __name__ == "__main__":
    a = sys.argv
    if a[1] == "sample":
        sample(a[2], a[3], a[4], a[5], int(a[6]), float(a[7]), float(a[8]), int(a[9]), a[10], a[11])
    elif a[1] == "gate":
        gate(a[2], a[3], float(a[4]))
    elif a[1] == "report":
        report(a[2], a[3], int(a[4]), int(a[5]), int(a[6]), int(a[7]), int(a[8]), int(a[9]), float(a[10]),
               a[11], a[12], a[13], a[14], a[15], int(a[16]), int(a[17]), int(a[18]), int(a[19]), a[20])
    elif a[1] == "threads":
        t = thread_table(a[2], a[3])
        print_threads(t) if t else print("no thread snapshots")
    else:
        sys.exit("usage: sched.py sample|gate|report|threads ...")
