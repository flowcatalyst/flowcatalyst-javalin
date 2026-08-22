package io.flowcatalyst.platform.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/// Port of `internal/platform/seed/event_types_test.go` plus the equivalent
/// shape checks for the role catalogue.
class PlatformCatalogueTest {

    @Test
    void eventTypeCatalogueShape() {
        List<PlatformEventTypes.Definition> defs = PlatformEventTypes.all();
        assertThat(defs).hasSizeGreaterThanOrEqualTo(41);
        assertThat(defs).hasSize(72);
        Set<String> seen = new HashSet<>();
        for (var d : defs) {
            assertThat(seen.add(d.code())).as("duplicate definition: %s", d.code()).isTrue();
            assertThat(d.code()).as("unexpected prefix on %s — must be platform:iam or platform:admin", d.code())
                    .matches("^platform:(iam|admin):.*");
            assertThat(d.name()).as("%s: empty name", d.code()).isNotEmpty();
            assertThat(d.code().split(":", -1)).hasSize(4);
        }
    }

    @Test
    void schemasAlignedWithDefinitions() {
        Set<String> codes = new HashSet<>();
        for (var d : PlatformEventTypes.all()) {
            codes.add(d.code());
            assertThat(PlatformEventSchemas.all()).as("definition %s has no schema", d.code()).containsKey(d.code());
            assertThat(d.schema()).as("definition %s carries its schema", d.code()).isNotNull();
        }
        for (String code : PlatformEventSchemas.all().keySet()) {
            assertThat(codes).as("schema %s has no matching definition", code).contains(code);
        }
    }

    @Test
    void schemasAreValidJsonObjects() {
        Map<String, JsonNode> schemas = PlatformEventSchemas.all();
        assertThat(schemas).hasSizeGreaterThanOrEqualTo(41);
        for (var e : schemas.entrySet()) {
            JsonNode s = e.getValue();
            assertThat(s.isObject()).as("schema %s: must be a JSON object", e.getKey()).isTrue();
            assertThat(s.path("type").asText()).as("schema %s: type must be object", e.getKey()).isEqualTo("object");
            assertThat(s.has("properties")).as("schema %s: missing properties", e.getKey()).isTrue();
            assertThat(s.has("required")).as("schema %s: missing required", e.getKey()).isTrue();
            assertThat(s.path("$schema").asText()).isEqualTo("http://json-schema.org/draft-07/schema#");
        }
    }

    @Test
    void noWebhookOrDeliveryEvents() {
        for (var d : PlatformEventTypes.all()) {
            assertThat(d.code()).doesNotContain("webhook").doesNotContain("delivery");
        }
    }

    @Test
    void titleCase() {
        assertThat(PlatformEventTypes.titleCase("created")).isEqualTo("Created");
        assertThat(PlatformEventTypes.titleCase("roles-assigned")).isEqualTo("Roles Assigned");
        assertThat(PlatformEventTypes.titleCase("client-access")).isEqualTo("Client Access");
        assertThat(PlatformEventTypes.titleCase("")).isEqualTo("");
        assertThat(PlatformEventTypes.titleCase("already Done")).isEqualTo("Already Done");
    }

    @Test
    void roleCatalogueShape() {
        List<RoleDefinition> roles = PlatformRoles.all();
        assertThat(roles).hasSize(14);
        assertThat(roles).extracting(RoleDefinition::name).containsExactly(
                "platform:super-admin", "platform:admin", "platform:admin-readonly",
                "platform:iam-admin", "platform:iam-readonly", "platform:client-admin",
                "platform:auth-admin", "platform:auth-readonly", "platform:ai-agent-readonly",
                "platform:messaging-admin", "platform:viewer", "platform:portal-administrator",
                "platform:developer", "platform:application-service");
        int total = 0;
        for (RoleDefinition r : roles) {
            assertThat(r.source()).isEqualTo("CODE");
            assertThat(r.applicationCode()).isEqualTo("platform");
            assertThat(r.name()).startsWith("platform:");
            assertThat(r.shortName()).doesNotContain(":");
            assertThat(r.permissions()).isNotEmpty().doesNotHaveDuplicates();
            total += r.permissions().size();
        }
        assertThat(total).isEqualTo(151);
        assertThat(roles.get(0).permissions()).containsExactly(Permissions.ADMIN_ALL);
        assertThat(roles.get(13).permissions()).isEqualTo(Permissions.APPLICATION_SERVICE);
    }
}
