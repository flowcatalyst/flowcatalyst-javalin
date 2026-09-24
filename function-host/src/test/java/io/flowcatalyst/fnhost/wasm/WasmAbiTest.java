package io.flowcatalyst.fnhost.wasm;

import io.flowcatalyst.fnhost.wasm.WasmAbi.GuestReply;
import io.flowcatalyst.fnhost.wasm.WasmAbi.Malformed;
import io.flowcatalyst.function.Caller;
import io.flowcatalyst.function.FunctionAddress;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.result.Result.Err;
import io.flowcatalyst.sdk.result.Result.Ok;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// The invocation ABI's two JSON shapes (`docs/spec/function-wasm-runtime.md`
/// §3): every [Caller] case in, and the reply shape out as a pinned
/// accept/reject table.
class WasmAbiTest {

    @Test
    void aPrincipalCallerCarriesEveryComponentOfTheRecord() {
        var principal = new Caller.Principal("prn_1", "SERVICE", "CLIENT", List.of("clt_1", "clt_2"),
                List.of("role-a"), List.of("app_1"), false, Set.of("b:perm", "a:perm"));

        JsonNode caller = encode(principal).path("caller");

        assertThat(caller.path("kind").asString()).isEqualTo("principal");
        assertThat(caller.path("id").asString()).isEqualTo("prn_1");
        assertThat(caller.path("type").asString()).isEqualTo("SERVICE");
        assertThat(caller.path("tier").asString()).isEqualTo("CLIENT");
        assertThat(strings(caller.path("clients"))).containsExactly("clt_1", "clt_2");
        assertThat(strings(caller.path("roles"))).containsExactly("role-a");
        assertThat(strings(caller.path("applications"))).containsExactly("app_1");
        assertThat(caller.path("allApplications").isBoolean()).isTrue();
        assertThat(caller.path("allApplications").asBoolean()).isFalse();
        assertThat(strings(caller.path("permissions")))
                .as("sorted, so the same caller always encodes to the same bytes").containsExactly("a:perm", "b:perm");
    }

    @Test
    void platformAndAnonymousCallersAreJustTheirKind() {
        assertThat(encode(Caller.Platform.INSTANCE).path("caller").toString()).isEqualTo("{\"kind\":\"platform\"}");
        assertThat(encode(Caller.Anonymous.INSTANCE).path("caller").toString()).isEqualTo("{\"kind\":\"anonymous\"}");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            # rule                          | output                                                        | status | body
            text body                       | {"status":201,"body":"héllo"}                                  | 201    | héllo
            base64 body                     | {"status":200,"bodyBase64":"aGk="}                             | 200    | hi
            no body at all                  | {"status":204}                                                 | 204    |
            headers of string arrays        | {"status":200,"headers":{"a":["1","2"]},"body":"x"}            | 200    | x
            unknown keys ignored            | {"status":200,"body":"x","extra":true}                         | 200    | x
            null body is no body            | {"status":200,"body":null}                                     | 200    |
            """)
    void acceptedReplies(String rule, String output, int status, String body) {
        var decoded = WasmAbi.decode(output.getBytes(StandardCharsets.UTF_8));
        assertThat(decoded).as(rule).isInstanceOfSatisfying(Ok.class, ok -> {
            GuestReply reply = (GuestReply) ok.value();
            assertThat(reply.status()).isEqualTo(status);
            assertThat(new String(reply.body(), StandardCharsets.UTF_8)).isEqualTo(body == null ? "" : body);
        });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            # rule                          | output
            not JSON                        | this is not the result shape
            not an object                   | [200]
            no status                       | {"body":"x"}
            status not an integer           | {"status":"200"}
            status below range              | {"status":99}
            status above range              | {"status":600}
            both bodies                     | {"status":200,"body":"x","bodyBase64":"eA=="}
            body not a string               | {"status":200,"body":{"a":1}}
            bodyBase64 not base64           | {"status":200,"bodyBase64":"!!!"}
            headers not an object           | {"status":200,"headers":["a"]}
            header value not an array       | {"status":200,"headers":{"a":"1"}}
            header array of non-strings     | {"status":200,"headers":{"a":[1]}}
            """)
    void malformedReplies(String rule, String output) {
        assertThat(WasmAbi.decode(output.getBytes(StandardCharsets.UTF_8))).as(rule)
                .isInstanceOfSatisfying(Err.class, err -> assertThat(err.error()).isInstanceOf(Malformed.class));
    }

    private static JsonNode encode(Caller caller) {
        Request request = new Request(new FunctionAddress("a", "s", "n"), 3, "inv", "GET", "/p", null, null,
                Map.of(), Map.of(), Map.of(), new byte[0], null, caller);
        return Json.MAPPER.readTree(WasmAbi.encode(request));
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }
}
