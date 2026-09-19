package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `FunctionVersionRepository` against the embedded Postgres (spec
/// `function-registry.md` §6.2, §8 M5, M9, M10, M14).
class FunctionVersionRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final FunctionRepository FUNCTION_REPO = new FunctionRepository(DS);
    private static final FunctionVersionRepository REPO = new FunctionVersionRepository(DS);
    private static final UnitOfWork UOW = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();
    private static final ClientCeilings UNRESTRICTED = ClientCeilings.of(DEFAULTS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private static final String MINIMAL_JVM = """
            {
              "runtime": "jvm",
              "entrypoint": "com.acme.billing.CreateInvoice",
              "endpoints": [ { "path": "/events/invoice-created", "auth": "webhook" } ],
              "subscriptions": [ { "eventType": "billing:invoices:invoice:created", "path": "/events/invoice-created" } ]
            }
            """;

    private static Manifest manifest() {
        return Manifest.parseStrict(Json.MAPPER.readTree(MINIMAL_JVM), Runtime.JVM, DEFAULTS, UNRESTRICTED);
    }

    private static String fresh() {
        return "t" + Long.toString(SEQ.incrementAndGet(), 36);
    }

    private static Function createFunction() {
        Function f = Function.create(fresh(),
                FunctionAddress.of(new DnsLabel("app-" + RUN + "-" + fresh()), new DnsLabel("svc"), new DnsLabel("fn")),
                FunctionOwner.ofClientId(fresh()), Runtime.JVM, null);
        UOW.inTransaction(tx -> {
            FUNCTION_REPO.persist(f, tx.dbTx());
            return null;
        });
        return f;
    }

    private static Digest digest(String suffix) {
        return Digest.parse("sha256:" + suffix.repeat(64).substring(0, 64));
    }

    // ── round-trip ─────────────────────────────────────────────────────────

    @Test
    void publishFindAndListRoundTrip() {
        Function f = createFunction();
        FunctionVersion v = FunctionVersion.publish(f.id(), 1, "oci://artifact", digest("a"), "bundle-text",
                "oci://sig-ref", new SignerIdentity("https://issuer", "subject"), manifest(), "prn_1", Instant.now());
        UOW.inTransaction(tx -> {
            REPO.persist(v, tx.dbTx());
            return null;
        });

        FunctionVersion reloaded = REPO.findById(v.id()).orElseThrow();
        assertThat(reloaded.functionId()).isEqualTo(f.id());
        assertThat(reloaded.version()).isEqualTo(1);
        assertThat(reloaded.artifactRef()).isEqualTo("oci://artifact");
        assertThat(reloaded.digest()).isEqualTo(digest("a"));
        assertThat(reloaded.signatureBundle()).isEqualTo("bundle-text");
        assertThat(reloaded.signer()).isEqualTo(new SignerIdentity("https://issuer", "subject"));
        assertThat(reloaded.manifest().entrypoint()).isEqualTo("com.acme.billing.CreateInvoice");
        assertThat(reloaded.state()).isInstanceOf(FunctionVersion.VersionState.Published.class);

        assertThat(REPO.findByFunctionAndVersion(f.id(), 1)).map(FunctionVersion::id).contains(v.id());
        assertThat(REPO.findByFunctionAndDigest(f.id(), digest("a"))).map(FunctionVersion::id).contains(v.id());
        assertThat(REPO.listByFunction(f.id())).extracting(FunctionVersion::id).containsExactly(v.id());
        assertThat(REPO.findByIds(java.util.List.of(v.id(), "fnv_doesnotexist"))).containsOnlyKeys(v.id());
    }

    // ── §8 M5: absent limit frozen to min(default, ceiling) in the row ────────

    @Test
    void parseStrictFreezesAnAbsentLimitToTheCeilingAndTheRowKeepsIt() {
        ClientCeilings lowCeiling = new ClientCeilings(5_000, 10, DEFAULTS.wasmMemoryMb(), DEFAULTS.dbPoolSize());
        Manifest frozen = Manifest.parseStrict(Json.MAPPER.readTree(MINIMAL_JVM), Runtime.JVM, DEFAULTS, lowCeiling);
        assertThat(frozen.limits().maxDurationMs()).as("min(default 30000, ceiling 5000)").isEqualTo(5_000);
        assertThat(frozen.limits().maxConcurrency()).as("min(default 32, ceiling 10)").isEqualTo(10);

        Function f = createFunction();
        FunctionVersion v = FunctionVersion.publish(f.id(), 1, "oci://artifact", digest("b"), null, null, null,
                frozen, "prn_1", Instant.now());
        UOW.inTransaction(tx -> {
            REPO.persist(v, tx.dbTx());
            return null;
        });

        FunctionVersion reloaded = REPO.findById(v.id()).orElseThrow();
        assertThat(reloaded.manifest().limits().maxDurationMs()).as("frozen in the row, not recomputed").isEqualTo(5_000);
        assertThat(reloaded.manifest().limits().maxConcurrency()).isEqualTo(10);
    }

    // ── §8 M9: a version's content is immutable through persist ─────────────

    @Test
    void persistNeverOverwritesTheOriginalContentOnlyStateAndTimestamps() {
        Function f = createFunction();
        FunctionVersion original = FunctionVersion.publish(f.id(), 1, "oci://original", digest("c"), null, null, null,
                manifest(), "prn_1", Instant.now());
        UOW.inTransaction(tx -> {
            REPO.persist(original, tx.dbTx());
            return null;
        });

        Instant readyAt = Instant.now().plusSeconds(30);
        FunctionVersion copy = new FunctionVersion(original.id(), original.functionId(), original.version(),
                "oci://DIFFERENT", digest("d"), null, null, null, manifest(),
                new FunctionVersion.VersionState.Ready(readyAt), original.publishedBy(), original.publishedAt());
        UOW.inTransaction(tx -> {
            REPO.persist(copy, tx.dbTx());
            return null;
        });

        FunctionVersion reloaded = REPO.findById(original.id()).orElseThrow();
        assertThat(reloaded.artifactRef()).as("original artifactRef kept").isEqualTo("oci://original");
        assertThat(reloaded.digest()).as("original digest kept").isEqualTo(digest("c"));
        assertThat(reloaded.state()).isEqualTo(new FunctionVersion.VersionState.Ready(readyAt));
    }

    /// A `Retired` version that was once `Ready` keeps its `ready_at` in the
    /// row (spec §6.2) — persisting the `Retired` copy must not null it out.
    @Test
    void retiringAVersionThatWasReadyKeepsTheOriginalReadyAtInTheRow() {
        Function f = createFunction();
        FunctionVersion published = FunctionVersion.publish(f.id(), 1, "oci://artifact", digest("e"), null, null, null,
                manifest(), "prn_1", Instant.now());
        UOW.inTransaction(tx -> {
            REPO.persist(published, tx.dbTx());
            return null;
        });
        Instant readyAt = Instant.now().plusSeconds(10);
        FunctionVersion ready = published.markReady(readyAt);
        UOW.inTransaction(tx -> {
            REPO.persist(ready, tx.dbTx());
            return null;
        });

        Instant retiredAt = readyAt.plusSeconds(20);
        FunctionVersion retired = ready.retire(retiredAt);
        UOW.inTransaction(tx -> {
            REPO.persist(retired, tx.dbTx());
            return null;
        });

        Instant rawReadyAt = rawReadyAt(published.id());
        assertThat(rawReadyAt).as("ready_at column preserved through the retire update")
                .isEqualTo(readyAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    private static Instant rawReadyAt(String versionId) {
        try (Connection c = DS.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT ready_at FROM fn_versions WHERE id = ?")) {
            ps.setString(1, versionId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, java.time.OffsetDateTime.class).toInstant();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── §8 M10: nextVersion serialises two concurrent publishes ─────────────

    @Test
    void nextVersionSerialisesTwoConcurrentPublishesInsteadOfRacing() throws Exception {
        Function f = createFunction();
        CountDownLatch tx1HasVersion = new CountDownLatch(1);
        CountDownLatch releaseTx1 = new CountDownLatch(1);
        CompletableFuture<Integer> tx1Version = new CompletableFuture<>();
        CompletableFuture<Void> tx1Done = new CompletableFuture<>();

        Thread t1 = Thread.ofVirtual().unstarted(() -> {
            try {
                UOW.inTransaction(tx -> {
                    int v = REPO.nextVersion(f.id(), tx.dbTx());
                    tx1Version.complete(v);
                    tx1HasVersion.countDown();
                    try {
                        releaseTx1.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    FunctionVersion fv = FunctionVersion.publish(f.id(), v, "oci://v" + v, digest("f" + v), null, null,
                            null, manifest(), "prn_1", Instant.now());
                    REPO.persist(fv, tx.dbTx());
                    return null;
                });
                tx1Done.complete(null);
            } catch (RuntimeException e) {
                tx1Done.completeExceptionally(e);
            }
        });
        t1.start();

        assertThat(tx1HasVersion.await(5, TimeUnit.SECONDS)).as("tx1 acquired its version").isTrue();
        assertThat(tx1Version.get(2, TimeUnit.SECONDS)).isEqualTo(1);

        CompletableFuture<Integer> tx2Version = new CompletableFuture<>();
        Thread t2 = Thread.ofVirtual().start(() -> {
            UOW.inTransaction(tx -> {
                int v = REPO.nextVersion(f.id(), tx.dbTx());
                tx2Version.complete(v);
                return null;
            });
        });

        // tx1's transaction is still open (holding the FOR UPDATE lock), so tx2
        // must NOT have returned yet — the mutant that removes FOR UPDATE lets it
        // race straight through and return 1.
        Thread.sleep(300);
        assertThat(tx2Version.isDone()).as("tx2 must still be blocked on tx1's row lock").isFalse();

        releaseTx1.countDown();
        tx1Done.get(5, TimeUnit.SECONDS);

        assertThat(tx2Version.get(10, TimeUnit.SECONDS)).as("tx2 sees tx1's committed version 1").isEqualTo(2);
        t1.join(5_000);
        t2.join(5_000);
    }

    @Test
    void nextVersionRejectsAnUnknownFunction() {
        assertThatThrownBy(() -> UOW.inTransaction(tx -> REPO.nextVersion("fnc_doesnotexist", tx.dbTx())))
                .hasMessageContaining("Function_NOT_FOUND");
    }

    // ── review fix, slice B3: lockById serialises MarkVersionReady's race ───

    /// Deterministic interleaving (the `nextVersion` test's own pattern
    /// above): tx1 locks the row and holds it; tx2's `lockById` call must
    /// block until tx1 commits, then observe tx1's committed state — never
    /// the pre-lock snapshot both callers could otherwise have read. This is
    /// what makes two racing `MarkVersionReady` calls serialise instead of
    /// both reading `Published` and both writing `version:ready`. The mutant
    /// that drops `FOR UPDATE` lets tx2 race straight through without
    /// blocking and read the stale `Published` state.
    @Test
    void lockByIdBlocksASecondCallerUntilTheFirstTransactionCommits() throws Exception {
        Function f = createFunction();
        FunctionVersion v = FunctionVersion.publish(f.id(), 1, "oci://artifact", digest("2"), null, null, null,
                manifest(), "prn_1", Instant.now());
        UOW.inTransaction(tx -> {
            REPO.persist(v, tx.dbTx());
            return null;
        });

        CountDownLatch tx1HasLock = new CountDownLatch(1);
        CountDownLatch releaseTx1 = new CountDownLatch(1);
        CompletableFuture<Void> tx1Done = new CompletableFuture<>();

        Thread t1 = Thread.ofVirtual().unstarted(() -> {
            try {
                UOW.inTransaction(tx -> {
                    FunctionVersion locked = REPO.lockById(v.id(), tx.dbTx()).orElseThrow();
                    tx1HasLock.countDown();
                    try {
                        releaseTx1.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    REPO.persist(locked.markReady(Instant.now()), tx.dbTx());
                    return null;
                });
                tx1Done.complete(null);
            } catch (RuntimeException e) {
                tx1Done.completeExceptionally(e);
            }
        });
        t1.start();

        assertThat(tx1HasLock.await(5, TimeUnit.SECONDS)).as("tx1 acquired the row lock").isTrue();

        CompletableFuture<FunctionVersion.VersionState> tx2State = new CompletableFuture<>();
        Thread t2 = Thread.ofVirtual().start(() -> {
            UOW.inTransaction(tx -> {
                FunctionVersion locked = REPO.lockById(v.id(), tx.dbTx()).orElseThrow();
                tx2State.complete(locked.state());
                return null;
            });
        });

        // tx1's transaction is still open (holding the FOR UPDATE lock), so tx2 must NOT have
        // returned yet — the mutant that removes FOR UPDATE lets it race straight through.
        Thread.sleep(300);
        assertThat(tx2State.isDone()).as("tx2 must still be blocked on tx1's row lock").isFalse();

        releaseTx1.countDown();
        tx1Done.get(5, TimeUnit.SECONDS);

        assertThat(tx2State.get(10, TimeUnit.SECONDS))
                .as("tx2 sees tx1's committed Ready state, not the pre-lock Published snapshot")
                .isInstanceOf(FunctionVersion.VersionState.Ready.class);
        t1.join(5_000);
        t2.join(5_000);
    }

    // ── §8 M14: fn_versions.manifest with unknown keys reads ────────────────

    @Test
    void manifestWithUnknownTopLevelKeysReadsWithoutThrowing() throws SQLException {
        Function f = createFunction();
        String id = "fnv_" + fresh();
        String manifestJson = """
                {"runtime":"jvm","entrypoint":"com.acme.Foo","unknownTopLevelField":"whatever",
                 "limits":{"maxDurationMs":5000,"maxConcurrency":10}}""";
        try (Connection c = DS.getConnection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO fn_versions (id, function_id, version, artifact_ref, digest, manifest, state, published_by)
                VALUES (?, ?, 1, 'oci://x', ?, ?::jsonb, 'PUBLISHED', ?)""")) {
            ps.setString(1, id);
            ps.setString(2, f.id());
            ps.setString(3, digest("9").value());
            ps.setString(4, manifestJson);
            ps.setString(5, "prn_1");
            ps.executeUpdate();
        }

        FunctionVersion reloaded = REPO.findById(id).orElseThrow();
        assertThat(reloaded.manifest().runtime()).isEqualTo(Runtime.JVM);
        assertThat(reloaded.manifest().entrypoint()).isEqualTo("com.acme.Foo");
        assertThat(reloaded.manifest().limits().maxDurationMs()).isEqualTo(5000);
        assertThat(reloaded.manifest().limits().maxConcurrency()).isEqualTo(10);
    }
}
