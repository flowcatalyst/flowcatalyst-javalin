package io.flowcatalyst.platform.shared.json;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/// Pins jsonb's text form: `": "` / `", "` separators, keys shorter-first
/// then bytewise, nested arrays and objects, scalar escaping.
class PgJsonbTest {

    @Test
    void rendersLikePostgresJsonbOut() {
        var node = Json.MAPPER.readTree("{\"type\":\"object\",\"$schema\":\"x\",\"properties\":{\"b\":{\"type\":\"string\"},\"a\":[1,2.5,null,true]},\"required\":[\"b\"],\"z\":\"q\\\"\\n\"}");
        assertThat(PgJsonb.render(node)).isEqualTo(
                "{\"z\": \"q\\\"\\n\", \"type\": \"object\", \"$schema\": \"x\", \"required\": [\"b\"], \"properties\": {\"a\": [1, 2.5, null, true], \"b\": {\"type\": \"string\"}}}");
    }

    @Test
    void keyOrderIsLengthThenBytes() {
        assertThat(PgJsonb.compareKeys("bb", "a")).isPositive();
        assertThat(PgJsonb.compareKeys("ab", "aa")).isPositive();
        assertThat(PgJsonb.compareKeys("a", "a")).isZero();
    }
}
