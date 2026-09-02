package io.flowcatalyst.platform.dispatchjob.settled;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.tsid.Tsid;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static org.assertj.core.api.Assertions.assertThat;

/// `POST /api/dispatch/settled` end to end through Javalin (dispatch-seam
/// spec §6): the request/response shapes, per-item independent HMAC
/// verification, the default reason, the idempotency guard, and the
/// 400/401/200 rules.
@SuppressWarnings("deprecation") // JsonNode#asText() — see DispatchJobRepository's own class doc
class SettledApiTest {

    private static final String APP_KEY = "settled-test-app-key-" + RUN;
    private static TestHttp http;
    private static HmacTokenVerifier verifier;
    private static DispatchJobRepository repo;

    @BeforeAll
    static void start() {
        repo = new DispatchJobRepository(DS);
        verifier = HmacTokenVerifier.fromAppKey(APP_KEY);
        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            SettledApi.register(cfg.routes, new SettledApi.State(repo, verifier));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static DispatchJob reload(String id) {
        return repo.findById(id).orElseThrow();
    }

    @Test
    void validTokensSettleAndInvalidOnesAreDroppedFromTheSameBatch() {
        String good = seedWriteRow(Seed.of(code("settled")).withStatus("QUEUED"));
        String bad = seedWriteRow(Seed.of(code("settled")).withStatus("QUEUED"));

        String body = """
                {"reason":"router ack","jobs":[
                    {"id":"%s","token":"%s"},
                    {"id":"%s","token":"forged-token-value"}
                ]}""".formatted(good, verifier.sign(good), bad);
        var r = http.post("/api/dispatch/settled", body);

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode resp = json(r);
        assertThat(resp.get("settled").asInt()).isEqualTo(1);
        assertThat(resp.get("ids")).extracting(JsonNode::asText).containsExactly(good);

        DispatchJob goodAfter = reload(good);
        assertThat(goodAfter.status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(goodAfter.scheduledFor()).isNull();
        assertThat(goodAfter.lastError()).isEqualTo("router ack");

        assertThat(reload(bad).status()).as("bad token — untouched").isEqualTo(DispatchJobStatus.QUEUED);
    }

    @Test
    void blankReasonFallsBackToTheDefault() {
        String id = seedWriteRow(Seed.of(code("settled")).withStatus("PROCESSING"));
        var body = "{\"jobs\":[{\"id\":\"%s\",\"token\":\"%s\"}]}".formatted(id, verifier.sign(id));
        var r = http.post("/api/dispatch/settled", body);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(reload(id).lastError()).isEqualTo(SettledApi.DEFAULT_REASON);
    }

    @Test
    void everyTokenFailingIsA401NotA400() {
        String id = seedWriteRow(Seed.of(code("settled")).withStatus("QUEUED"));
        var body = "{\"jobs\":[{\"id\":\"%s\",\"token\":\"forged\"}]}".formatted(id);
        var r = http.post("/api/dispatch/settled", body);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(json(r).get("settled").asInt()).isZero();
        assertThat(reload(id).status()).isEqualTo(DispatchJobStatus.QUEUED);
    }

    @Test
    void emptyJobsIsALegalNoOp() {
        var r = http.post("/api/dispatch/settled", "{\"jobs\":[]}");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("settled").asInt()).isZero();
        assertThat(json(r).has("ids")).isFalse();
    }

    @Test
    void malformedBodyIsA400() {
        var r = http.post("/api/dispatch/settled", "not json");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("settled").asInt()).isZero();
    }

    @Test
    void tooManyJobsIsA400() {
        var sb = new StringBuilder("{\"jobs\":[");
        for (int i = 0; i < SettledApi.MAX_JOBS_PER_REQUEST + 1; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"x").append(i).append("\",\"token\":\"y\"}");
        }
        sb.append("]}");
        var r = http.post("/api/dispatch/settled", sb.toString());
        assertThat(r.statusCode()).isEqualTo(400);
    }

    /// Audit finding (test-gap): the `MAX_BODY_BYTES` (1 MiB) branch had no
    /// test at all before this. Also pins that the cap is now checked
    /// against the declared `Content-Length` BEFORE the body is buffered
    /// (`ctx.contentLength()`), not only after — see `SettledApi#serve`.
    @Test
    void oversizedBodyIsA400() {
        var sb = new StringBuilder("{\"jobs\":[{\"id\":\"x\",\"token\":\"");
        sb.append("y".repeat(2 * 1024 * 1024)); // over MAX_BODY_BYTES (1 MiB)
        sb.append("\"}]}");
        var r = http.post("/api/dispatch/settled", sb.toString());
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("settled").asInt()).isZero();
    }

    @Test
    void aTerminalStatusIsNeverResurrected() {
        String id = seedWriteRow(Seed.of(code("settled")).withStatus("COMPLETED"));
        var body = "{\"jobs\":[{\"id\":\"%s\",\"token\":\"%s\"}]}".formatted(id, verifier.sign(id));
        var r = http.post("/api/dispatch/settled", body);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).get("settled").asInt()).as("the status IN (...) guard drops a terminal row").isZero();
        assertThat(reload(id).status()).isEqualTo(DispatchJobStatus.COMPLETED);
    }

    @Test
    void aBadTokenForOneUnknownIdDoesNotAffectAnything() {
        var body = "{\"jobs\":[{\"id\":\"%s\",\"token\":\"nope\"}]}".formatted(Tsid.generate());
        var r = http.post("/api/dispatch/settled", body);
        assertThat(r.statusCode()).isEqualTo(401);
    }
}
