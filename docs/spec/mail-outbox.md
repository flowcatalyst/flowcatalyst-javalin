# Spec — mail leaves the request path (owner ruling 2026-09-06, `vertx-plan.md` §3b rule 7)

## 1. Why

`SmtpMailService.send` opens a plain socket with a 30 s connect timeout and `soTimeout`
and holds an SMTP conversation (≈6 round trips) inside the request that asked for it:
login MFA e-mail codes (`Mfa`), password-reset links (`ResetLinks`), notifications
(`Notifications`). On a virtual thread every timed socket read is a kernel round trip
(`admission.md` §0), and a slow mail server holds the login request and its `LOGIN`
bulkhead permit for up to 30 s per read. Go does the same inline (`shared/email`), which
is recorded as a Go defect in `docs/go-mirror/2026-09-06-go-fix-list.md` (G9).

## 2. Shape

- **Table `mail_outbox`** (Flyway `V8__mail_outbox.sql`, additive): `id text pk` (TSID),
  `to_addr text`, `subject text`, `html text`, `status text` check in
  (`PENDING`,`SENT`,`FAILED`), `attempts int default 0`, `next_attempt_at timestamptz`,
  `last_error text null`, `created_at timestamptz`, `sent_at timestamptz null`; index on
  `(status, next_attempt_at)`.
- **`OutboxMailService implements MailService`** (`platform/mail`): `send(Mail)` inserts
  one `PENDING` row with `next_attempt_at = now()` in its own short transaction through
  the pool (the `MailService` contract has no transaction to join; a crash between the
  caller's commit and this insert loses the mail exactly as a crash before SMTP does
  today — the caller's retry path is unchanged). Returns at once. This is what
  `MailService.fromEnv` now returns, always, SMTP configured or not.
- **`MailSender`** (`platform/mail`, background, the `DispatchJobReaper` shape:
  `start(pool, transport, clock, interval)` / `AutoCloseable`): every `interval`
  (2 s) claims up to 10 rows `WHERE status='PENDING' AND next_attempt_at <= now()
  ORDER BY created_at FOR UPDATE SKIP LOCKED`, hands each to the **transport** — the
  existing `SmtpMailService` when `FC_SMTP_HOST` is set, else `MailService.logging()` —
  and marks `SENT` (`sent_at`) or, on any exception, `attempts+1`,
  `next_attempt_at = now() + ladder[min(attempts, 4)]` with `ladder = {5, 15, 30, 60,
  120} s` (the dispatch-job ladder, `ProcessingApi.BACKOFF_LADDER_SECONDS`), `last_error`;
  after 6 attempts, `FAILED`. Timed waits in this loop are fine: it is a background
  platform thread, not a request path. Sending runs on the sender's own virtual thread
  per claimed batch, never on a request.
- **Retention:** `Purger` deletes `SENT` older than 7 days and `FAILED` older than 30.
- **Metrics:** `fc_mail_outbox_pending` (gauge, sampled per tick), `fc_mail_sent_total`,
  `fc_mail_failed_total`.
- Wired in `Server` (platform mode) and `StartCommand`; stopped in the graceful-stop
  order after the listener (in-flight requests may still enqueue; the sender drains
  nothing on stop — rows wait for the next start).

## 3. What does not change

`Mail`, `MailService`, the three callers, `SmtpMailService` itself, the rendered HTML,
the `FC_SMTP_*` variables. The parity corpus does not observe SMTP.

## 4. Tests (break-it-on-purpose each)

| # | Behaviour | Pin | Mutant |
|---|---|---|---|
| 1 | `send(Mail)` returns before any SMTP happens | `OutboxMailServiceTest`: a transport stub that blocks on a latch; `send` returns and the row is `PENDING` while the latch is still closed | send inline → the test hangs on the latch (bounded by the test timeout) |
| 2 | The sender delivers and marks `SENT` with `sent_at` | `MailSenderTest` over embedded Postgres with a recording transport | never mark → row stays `PENDING` and is claimed twice (assert the transport saw it once) |
| 3 | A failing transport advances `next_attempt_at` by the ladder and increments `attempts` | assert `next_attempt_at − now ≈ 5 s` after the first failure, `15 s` after the second | constant delay → second assertion fails |
| 4 | After 6 failures the row is `FAILED` and no longer claimed | count transport calls == 6 | no cap → 7th call observed |
| 5 | Two senders never deliver the same row | two `MailSender`s, 50 rows, transport counts deliveries per id == 1 | drop `SKIP LOCKED`/`FOR UPDATE` → duplicates |
| 6 | Login MFA request answers within 1 s while SMTP is blocked | `TwoFactorApiTest` addition: blocking transport, assert HTTP status and elapsed < 1 s | inline send → elapsed ≥ latch timeout |
