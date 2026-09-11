package io.flowcatalyst.parity;

import io.flowcatalyst.parity.model.Request;
import io.flowcatalyst.parity.model.Step;
import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// One test per normalisation rule (parity-harness spec §5), plus the
/// interaction the spec calls out explicitly: a JWT claim equal to a
/// captured id comes out as `«adminId»` *inside* the decoded claims (rule 4
/// applies first, then rule 1 recurses into what it decoded).
class NormaliserTest {

    private static final String BASE_URL = "http://127.0.0.1:54321";

    private static Vars vars() {
        return new Vars("admin@example.com", "hunter2-hunter2", "run1", "client-1", "app-1", "admin-1");
    }

    private static Step stepWith(List<String> unordered, List<Step.Ignore> ignore) {
        Request request = new Request("GET", "/dummy", null, null, null, null, null);
        return new Step("step", request, null, null, unordered, ignore, null);
    }

    private static StepRecord jsonRecord(JsonNode body) {
        return StepRecord.json(200, Map.of(), body);
    }

    private static ObjectNode obj() {
        return Json.MAPPER.createObjectNode();
    }

    // ── rule 1: own captured values → «name» ────────────────────────────

    @Test
    void rule1ReplacesACapturedValueWithItsName() {
        Vars vars = vars();
        vars.capture("etId", "evt_abc123");
        ObjectNode body = obj();
        body.put("id", "evt_abc123");
        body.put("other", "untouched");

        Normalised out = Normaliser.normalise(jsonRecord(body), vars, BASE_URL, stepWith(List.of(), List.of()));

        assertThat(out.body().get("id").asString()).isEqualTo("«etId»");
        assertThat(out.body().get("other").asString()).isEqualTo("untouched");
    }

    // ── rule 2: own base URL → «base» ────────────────────────────────────

    @Test
    void rule2ReplacesTheOwnBaseUrlEvenAsASubstring() {
        Vars vars = vars();
        ObjectNode body = obj();
        body.put("issuer", BASE_URL + "/auth");

        Normalised out = Normaliser.normalise(jsonRecord(body), vars, BASE_URL, stepWith(List.of(), List.of()));

        assertThat(out.body().get("issuer").asString()).isEqualTo("«base»/auth");
    }

    // ── rule 3: RFC 3339 timestamps → «time», whole-string match only ───

    @Test
    void rule3ReplacesAWholeRfc3339TimestampButNotAnEmbeddedOne() {
        Vars vars = vars();
        ObjectNode body = obj();
        body.put("createdAt", "2026-05-24T08:30:00.123456Z");
        body.put("message", "created at 2026-05-24T08:30:00.123456Z by admin");

        Normalised out = Normaliser.normalise(jsonRecord(body), vars, BASE_URL, stepWith(List.of(), List.of()));

        assertThat(out.body().get("createdAt").asString()).isEqualTo("«time»");
        assertThat(out.body().get("message").asString()).contains("2026-05-24T08:30:00.123456Z");
    }

    // ── rule 4: JWS strings → structured {header, claims} ───────────────

    @Test
    void rule4DecodesAJwsIntoStructuredHeaderAndClaims() {
        Vars vars = vars();
        String jwt = fakeJwt("{\"alg\":\"RS256\",\"kid\":\"k1\"}",
                "{\"sub\":\"admin-1\",\"iat\":1000,\"exp\":2000,\"updated_at\":900,\"jti\":\"abc\"}");
        ObjectNode body = obj();
        body.put("accessToken", jwt);

        Normalised out = Normaliser.normalise(jsonRecord(body), vars, BASE_URL, stepWith(List.of(), List.of()));

        JsonNode token = out.body().get("accessToken");
        assertThat(token.has("«jwt»")).isTrue();
        JsonNode claims = token.get("«jwt»").get("claims");
        assertThat(claims.get("iat").asString()).isEqualTo("«time»");
        assertThat(claims.get("exp").asString()).isEqualTo("«time»");
        assertThat(claims.get("updated_at").asString()).as("a row timestamp on each side's own clock").isEqualTo("«time»");
        assertThat(claims.get("jti").asString()).isEqualTo("«id»");
    }

    /// The interaction the spec calls out by name: a JWT whose `sub` equals a
    /// captured id comes out as `«adminId»` INSIDE the decoded claims — rule 4
    /// fires first (the string is not collapsed to an opaque sentinel), then
    /// rule 1 recurses into what it decoded.
    @Test
    void rule4ThenRule1InsideDecodedClaims() {
        Vars vars = vars();
        vars.capture("adminId", "admin-1");
        String jwt = fakeJwt("{\"alg\":\"RS256\"}", "{\"sub\":\"admin-1\"}");
        ObjectNode body = obj();
        body.put("accessToken", jwt);

        Normalised out = Normaliser.normalise(jsonRecord(body), vars, BASE_URL, stepWith(List.of(), List.of()));

        JsonNode claims = out.body().get("accessToken").get("«jwt»").get("claims");
        assertThat(claims.get("sub").asString()).isEqualTo("«adminId»");
    }

    @Test
    void aNonJwsThreeSegmentStringIsLeftAlone() {
        Vars vars = vars();
        ObjectNode body = obj();
        body.put("value", "not.a.jwt");

        Normalised out = Normaliser.normalise(jsonRecord(body), vars, BASE_URL, stepWith(List.of(), List.of()));

        assertThat(out.body().get("value").asString()).isEqualTo("not.a.jwt");
    }

    // ── rule 5: cookie values → «cookie» ─────────────────────────────────

    @Test
    void rule5MasksTheCookieValueButKeepsNameAndAttributes() {
        Vars vars = vars();
        StepRecord record = StepRecord.json(200,
                Map.of(ComparedHeaders.SET_COOKIE, "fc_session=abc.def.ghi; Path=/; HttpOnly; SameSite=Lax"),
                obj());

        Normalised out = Normaliser.normalise(record, vars, BASE_URL, stepWith(List.of(), List.of()));

        assertThat(out.headers().get(ComparedHeaders.SET_COOKIE))
                .isEqualTo("fc_session=«cookie»; HttpOnly; Path=/; SameSite=Lax");
    }

    /// The coordinator's amendment to rule 5: `Expires` is a timestamp too
    /// (RFC 1123, not RFC 3339, so rule 3 never reaches it on its own) — its
    /// value is masked the same way, the attribute name kept.
    @Test
    void rule5AlsoMasksTheExpiresAttributeValue() {
        Vars vars = vars();
        StepRecord record = StepRecord.json(200,
                Map.of(ComparedHeaders.SET_COOKIE,
                        "fc_session=abc.def.ghi; Path=/; Expires=Wed, 09 Jun 2027 10:18:14 GMT; HttpOnly"),
                obj());

        Normalised out = Normaliser.normalise(record, vars, BASE_URL, stepWith(List.of(), List.of()));

        assertThat(out.headers().get(ComparedHeaders.SET_COOKIE))
                .isEqualTo("fc_session=«cookie»; Expires=«time»; HttpOnly; Path=/");
    }

    // ── rule 6: unordered arrays sorted by normalised JSON text ─────────

    @Test
    void rule6SortsAnUnorderedArraySoDifferentOrdersCompareEqual() {
        Vars vars = vars();
        ObjectNode bodyA = obj();
        bodyA.putArray("items").add("b").add("a").add("c");
        ObjectNode bodyB = obj();
        bodyB.putArray("items").add("c").add("b").add("a");

        Normalised outA = Normaliser.normalise(jsonRecord(bodyA), vars, BASE_URL, stepWith(List.of("/items"), List.of()));
        Normalised outB = Normaliser.normalise(jsonRecord(bodyB), vars(), BASE_URL, stepWith(List.of("/items"), List.of()));

        assertThat(outA.body()).isEqualTo(outB.body());
    }

    @Test
    void withoutUnorderedADifferentOrderStillDiffers() {
        Vars vars = vars();
        ObjectNode bodyA = obj();
        bodyA.putArray("items").add("b").add("a");
        ObjectNode bodyB = obj();
        bodyB.putArray("items").add("a").add("b");

        Normalised outA = Normaliser.normalise(jsonRecord(bodyA), vars, BASE_URL, stepWith(List.of(), List.of()));
        Normalised outB = Normaliser.normalise(jsonRecord(bodyB), vars(), BASE_URL, stepWith(List.of(), List.of()));

        assertThat(outA.body()).isNotEqualTo(outB.body());
    }

    // ── rule 7: ignore pointers removed from both sides ─────────────────

    @Test
    void rule7RemovesAnIgnoredPointerIncludingAWildcardArraySegment() {
        Vars vars = vars();
        ObjectNode body = obj();
        var items = body.putArray("items");
        var item1 = items.addObject();
        item1.put("id", "1");
        item1.put("createdBy", "system-a");
        var item2 = items.addObject();
        item2.put("id", "2");
        item2.put("createdBy", "system-b");

        Normalised out = Normaliser.normalise(jsonRecord(body), vars, BASE_URL,
                stepWith(List.of(), List.of(new Step.Ignore("/items/*/createdBy", "not stable across seeds"))));

        assertThat(out.body().at("/items/0/createdBy").isMissingNode()).isTrue();
        assertThat(out.body().at("/items/1/createdBy").isMissingNode()).isTrue();
        assertThat(out.body().at("/items/0/id").asString()).isEqualTo("1");
    }

    private static String fakeJwt(String headerJson, String claimsJson) {
        String header = b64url(headerJson.getBytes(StandardCharsets.UTF_8));
        String claims = b64url(claimsJson.getBytes(StandardCharsets.UTF_8));
        String signature = b64url("sig".getBytes(StandardCharsets.UTF_8));
        return header + "." + claims + "." + signature;
    }

    private static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /// Rule 1's substring form: an id embedded in an error message or a
    /// derived id is masked by its capture name; a short captured value is not
    /// applied as a substring.
    @Test
    void rule1AlsoMasksACapturedValueEmbeddedInALongerString() {
        Vars vars = vars();
        vars.capture("etId", "evt_6G74TA21K0B7R");
        vars.capture("word", "ok");
        JsonNode body = Json.MAPPER.createObjectNode()
                .put("message", "EventType not found: evt_6G74TA21K0B7R")
                .put("derived", "evt_6G74TA21K0B7R-role-0")
                .put("status", "ok-ish");
        Normalised n = Normaliser.normalise(StepRecord.json(404, Map.of(), body), vars, "", stepWith(List.of(), List.of()));
        assertThat(n.body().get("message").asString()).isEqualTo("EventType not found: «etId»");
        assertThat(n.body().get("derived").asString()).isEqualTo("«etId»-role-0");
        assertThat(n.body().get("status").asString()).isEqualTo("ok-ish");
    }

    /// Rule 5: attribute order carries no meaning, so two orderings of the
    /// same attributes normalise to the same text.
    @Test
    void rule5ComparesCookieAttributesAsASet() {
        String go = Normaliser.maskCookie("fc_session=abc; Path=/; HttpOnly; Secure; SameSite=Lax");
        String java = Normaliser.maskCookie("fc_session=xyz; Path=/; Secure; HttpOnly; SameSite=Lax");
        assertThat(go).isEqualTo(java);
        assertThat(go).contains("HttpOnly").contains("Secure").contains("SameSite=Lax").doesNotContain("abc");
    }
}
