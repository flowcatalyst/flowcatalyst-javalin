# Go hand-off — findings from the drop-in pass of 2026-09-14

For the Go agent. Each item was found by comparing the Java platform with
the Go working tree (`f81fd5a` plus the uncommitted invitation change) on
2026-09-14; the full record is `docs/audit/2026-09-14-drop-in-pass.md` in
the Java repo.

1. **Trusted devices: nil slice on the wire.**
   `internal/platform/mfa/repository.go:277` (`FindTrustedDevicesByPrincipal`)
   declares `var out []TrustedDevice` and never assigns it when the user has
   none, so the JSON is `{"devices": null}`. The shared SPA
   (`TwoFactorSection.vue`, `v-if="devices.length"`) throws and the whole
   2FA card vanishes; three browser flows fail on Go and pass on Java.
   Fix: `out := []TrustedDevice{}` (or `make(...)`), so an empty list is
   `[]`. Java's repository returns a real list.

2. **Event-type `clientScoped` dropped on create** — ruling 2026-09-06 #7,
   Java fixed, Go mirror still pending; one catalogue browser flow fails on
   Go for it.

3. **Two "configurable" router settings are hard-coded.**
   `NOTIFICATION_BATCH_INTERVAL` (Go: `NewNotifier(url, 20, 10*time.Second)`
   at `internal/router/server.go:203`; the deployed task sets 300) and
   `FLOWCATALYST_CONFIG_INTERVAL` (`ServerConfig.ConfigPollInterval` is
   declared and never wired from the environment). Java reads both. Owner
   decides which behaviour the deployment wants; either way the Go docs
   and `router-env.md` should stop claiming they are read.

4. **Commit the invitation change.** Parity now carries 13 steps for it
   (`parity/scenarios/{auth/session,principals/principals-access,principals/principals-core}.json`)
   pinned against the working tree only; once committed, the corpus can be
   run against a fixed Go commit again.

5. **Already caught up (nothing to do):** the `platform:router` role and
   the 21 permission rows — the 22 Java-first allow-list entries were
   retired on 2026-09-14.

6. **Frontend:** the two files to take verbatim are listed in
   `2026-09-14-frontend-source-shared.md`.
