package io.flowcatalyst.sdk.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.flowcatalyst.sdk.FlowCatalystClient;
import io.flowcatalyst.sdk.StubServer;
import io.flowcatalyst.sdk.generated.model.CreateUserRequest;
import io.flowcatalyst.sdk.generated.model.PrincipalResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire behaviour of the app-managed-invitations flags on {@code
 * createUser} (spec: docs/spec/app-managed-invitations.md §1, §5): the two
 * optional booleans must serialise (or be omitted) correctly, and a returned
 * {@code inviteLink} must deserialise onto {@link PrincipalResponse}.
 */
class PrincipalsResourceTest {

    private StubServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new StubServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private FlowCatalystClient client() {
        return FlowCatalystClient.builder()
                .baseUrl(server.baseUrl())
                .clientCredentials("id", "secret")
                .retryDelay(Duration.ofMillis(1))
                .build();
    }

    @Test
    void sendInvitationFalseIsSerialisedAndReturnInviteLinkIsOmitted() {
        server.stubToken("tok");
        server.on("POST", "/api/principals/users", 201,
                "{\"id\":\"usr_1\",\"email\":\"a@example.com\",\"name\":\"A\"}");

        client().principals().createUser(new CreateUserRequest()
                .email("a@example.com")
                .name("A")
                .sendInvitation(false));

        String body = server.requests.getLast().body();
        assertTrue(body.contains("\"sendInvitation\":false"), body);
        assertFalse(body.contains("returnInviteLink"), body);
    }

    @Test
    void returnInviteLinkTrueDeserialisesTheLinkOntoTheResponse() {
        server.stubToken("tok");
        server.on("POST", "/api/principals/users", 201,
                "{\"id\":\"usr_1\",\"email\":\"a@example.com\",\"name\":\"A\","
                        + "\"inviteLink\":\"https://platform.example/set-password?token=abc\"}");

        PrincipalResponse response = client().principals().createUser(new CreateUserRequest()
                .email("a@example.com")
                .name("A")
                .returnInviteLink(true));

        String body = server.requests.getLast().body();
        assertTrue(body.contains("\"returnInviteLink\":true"), body);
        assertEquals("https://platform.example/set-password?token=abc", response.getInviteLink());
    }

    @Test
    void neitherFlagSetOmitsBothKeysFromTheRequestBody() {
        server.stubToken("tok");
        server.on("POST", "/api/principals/users", 201,
                "{\"id\":\"usr_1\",\"email\":\"a@example.com\",\"name\":\"A\"}");

        PrincipalResponse response = client().principals().createUser(new CreateUserRequest()
                .email("a@example.com")
                .name("A"));

        String body = server.requests.getLast().body();
        assertFalse(body.contains("sendInvitation"), body);
        assertFalse(body.contains("returnInviteLink"), body);
        assertNull(response.getInviteLink(), "inviteLink absent from the stub response must stay null");
    }
}
