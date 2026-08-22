#!/usr/bin/env python3
# Regenerates server/src/main/resources/db/migration/V1__baseline.sql from a
# `pg_dump --schema-only --no-owner --no-privileges` of a Go-migrated database.
# Usage: tools/make-baseline.py <go-schema.sql> server/src/main/resources/db/migration/V1__baseline.sql
# Only needed if the Go baseline is ever re-captured; see docs/database.md.
import re, sys, collections
src = sys.argv[1]; dst = sys.argv[2]
text = open(src).read()
hdr = re.compile(r'^--\n-- Name: (?P<name>.*?); Type: (?P<type>.*?); Schema: (?P<schema>.*?); Owner: .*?\n--\n', re.M)
parts = []
pos = 0
ms = list(hdr.finditer(text))
for i, m in enumerate(ms):
    end = ms[i+1].start() if i+1 < len(ms) else len(text)
    body = text[m.end():end]
    parts.append((m.group('name'), m.group('type'), body))
dated = re.compile(r'_\d{4}_\d{2}(?![0-9])')
stats = collections.Counter(); dropped = collections.Counter()
out = []
for name, typ, body in parts:
    dated_body = re.compile(r'public\.\w+_\d{4}_\d{2}(?![0-9])')
    if typ in ('TABLE ATTACH', 'INDEX ATTACH') or dated.search(name) or dated_body.search(body) or 'goose_db_version' in name:
        dropped[typ] += 1; continue
    # strip trailing psql noise, SET, etc. from body
    lines = []
    for ln in body.split('\n'):
        if ln.startswith('SET ') or ln.startswith('\\') or ln.startswith('SELECT pg_catalog.set_config'):
            continue
        if 'OWNER TO' in ln: continue
        lines.append(ln)
    body = '\n'.join(lines).strip('\n')
    # pg_dump qualifies constraint/index DDL with ONLY (and 'ON ONLY' for
    # partitioned parents, since it attaches per-partition indexes afterwards).
    # We create partitions after all indexes/constraints exist, so the plain
    # forms are what we want: partitions inherit indexes automatically.
    body = body.replace('ALTER TABLE ONLY public.', 'ALTER TABLE public.')
    body = body.replace(' ON ONLY public.', ' ON public.')
    if typ == 'TABLE':
        body = body.replace('CREATE TABLE public.', 'CREATE TABLE IF NOT EXISTS public.', 1)
    elif typ == 'SEQUENCE':
        body = body.replace('CREATE SEQUENCE public.', 'CREATE SEQUENCE IF NOT EXISTS public.', 1)
    elif typ == 'INDEX':
        body = re.sub(r'^CREATE (UNIQUE )?INDEX public\.', lambda m: 'CREATE %sINDEX IF NOT EXISTS public.' % (m.group(1) or ''), body, count=1, flags=re.M)
        body = re.sub(r'^CREATE (UNIQUE )?INDEX (\S+) ON', lambda m: 'CREATE %sINDEX IF NOT EXISTS %s ON' % (m.group(1) or '', m.group(2)), body, count=1, flags=re.M)
    stats[typ] += 1
    out.append((name, typ, body))
print('kept', dict(stats)); print('dropped', dict(dropped))
# group by type for readable sections, preserving pg_dump order within each group
order = ['SEQUENCE','TABLE','SEQUENCE OWNED BY','DEFAULT','CONSTRAINT','INDEX','FK CONSTRAINT']
assert set(stats) <= set(order), stats
w = []
w.append('''-- FlowCatalyst baseline schema (V1).
--
-- This is the schema produced by the Go service's goose migrations
-- (flowcatalyst-go/internal/migrate/sql/001..045) captured with
-- `pg_dump --schema-only` from PostgreSQL 18 and cleaned up for Flyway:
--
--   * pg_dump preamble (SET ..., \\restrict, set_config), OWNER TO and
--     psql meta-commands removed;
--   * the `goose_db_version` table is NOT part of the baseline. Java never
--     creates it; on a database adopted from Go it already exists and is
--     left untouched so a rollback to the Go service stays possible;
--   * the dated monthly partitions (`<parent>_YYYY_MM`) and their
--     ATTACH PARTITION / ATTACH INDEX statements are replaced by the DO block
--     at the end of this file, which creates month-1 .. month+3 partitions
--     relative to now() — exactly what Go migrations 019/022 do on a fresh
--     install. Forward-rolling and retention are a runtime concern
--     (Go: internal/stream/partition_manager.go; Java: its port).
--
-- Everything else (every table, column, default, constraint, index and
-- sequence) is identical to the Go schema. `IF NOT EXISTS` is used where it
-- is free so an accidental re-run on a Go-shaped database is harmless.
--
-- Rollback-to-Go rule: the Go service must be able to run against any
-- database migrated by this project. Until that rule is lifted, every
-- migration after V1 has to be additive and ignorable by Go (new nullable
-- columns, new tables, new indexes) — never rename/drop/retype anything Go
-- reads or writes, and never touch goose_db_version.
''')
titles = {
 'SEQUENCE':'Sequences','TABLE':'Tables','SEQUENCE OWNED BY':'Sequence ownership','DEFAULT':'Column defaults backed by sequences',
 'CONSTRAINT':'Primary keys and unique constraints','INDEX':'Indexes','FK CONSTRAINT':'Foreign keys'}
for typ in order:
    items = [o for o in out if o[1] == typ]
    if not items: continue
    w.append('\n-- ============================================================================')
    w.append('-- %s' % titles[typ])
    w.append('-- ============================================================================\n')
    for name, _, body in items:
        if typ == 'TABLE':
            w.append('-- %s' % name)
        w.append(body)
        w.append('')
w.append('''
-- ============================================================================
-- Initial monthly partitions (port of Go migrations 019 + 022)
-- ============================================================================
-- Creates `<parent>_YYYY_MM` RANGE partitions covering (this month - 1)
-- through (this month + 3) for each partitioned parent. Idempotent.
-- Note: month boundaries are computed in the session time zone, as in Go.

DO $partitions$
DECLARE
    parent_table TEXT;
    parents TEXT[] := ARRAY[
        'msg_events',
        'msg_events_read',
        'msg_dispatch_jobs',
        'msg_dispatch_jobs_read',
        'msg_dispatch_job_attempts',
        'msg_scheduled_job_instances',
        'msg_scheduled_job_instance_logs'
    ];
    m INTEGER;
    months_back CONSTANT INTEGER := 1;
    months_forward CONSTANT INTEGER := 3;
    start_ts TIMESTAMPTZ;
    end_ts TIMESTAMPTZ;
    partition_name TEXT;
BEGIN
    FOREACH parent_table IN ARRAY parents LOOP
        FOR m IN -months_back..months_forward LOOP
            start_ts := date_trunc('month', NOW()) + (m || ' months')::INTERVAL;
            end_ts := start_ts + INTERVAL '1 month';
            partition_name := parent_table || '_' || to_char(start_ts, 'YYYY_MM');

            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS public.%I PARTITION OF public.%I FOR VALUES FROM (%L) TO (%L)',
                partition_name,
                parent_table,
                start_ts,
                end_ts
            );
        END LOOP;
    END LOOP;
END
$partitions$;
''')
open(dst,'w').write('\n'.join(w))
