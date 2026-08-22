package io.flowcatalyst.platform.shared.apicommon;

import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApicommonShapesTest {

    record Item(String id) {
    }

    @Test
    void offsetPage() {
        var page = OffsetPage.of(List.of(new Item("a"), new Item("b")), 1, 2, 5);
        assertThat(Json.write(page))
                .isEqualTo("{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}],\"page\":1,\"size\":2,\"total\":5,\"total_pages\":3}");
        assertThat(Json.write(OffsetPage.of(null, 0, 20, 0)))
                .isEqualTo("{\"data\":[],\"page\":0,\"size\":20,\"total\":0,\"total_pages\":0}");
        assertThat(OffsetPage.of(List.of(), 0, 0, 7).totalPages()).isZero();
        assertThat(OffsetPage.of(List.of(), 0, 10, 10).totalPages()).isEqualTo(1);
        assertThat(OffsetPage.of(List.of(), 0, 10, 11).totalPages()).isEqualTo(2);
    }

    @Test
    void cursorResponse() {
        assertThat(Json.write(new CursorResponse<>(List.of(new Item("a")), "c2", true)))
                .isEqualTo("{\"items\":[{\"id\":\"a\"}],\"nextCursor\":\"c2\",\"hasMore\":true}");
        assertThat(Json.write(new CursorResponse<Item>(null, "", false)))
                .isEqualTo("{\"items\":[],\"hasMore\":false}");
        assertThat(Json.write(new CursorResponse<Item>(List.of(), null, false)))
                .isEqualTo("{\"items\":[],\"hasMore\":false}");
    }

    @Test
    void sizeOnlyResponse() {
        assertThat(Json.write(new SizeOnlyResponse<>(List.of(new Item("a"))))).isEqualTo("{\"items\":[{\"id\":\"a\"}]}");
        assertThat(Json.write(new SizeOnlyResponse<Item>(null))).isEqualTo("{\"items\":[]}");
    }

    @Test
    void smallEnvelopes() {
        assertThat(Json.write(new CreatedResponse("evt_1"))).isEqualTo("{\"id\":\"evt_1\"}");
        assertThat(Json.write(new StatusChangeResponse("Deactivated"))).isEqualTo("{\"message\":\"Deactivated\"}");
        assertThat(Json.write(new SuccessResponse(true, "done"))).isEqualTo("{\"success\":true,\"message\":\"done\"}");
        assertThat(Json.write(SuccessResponse.ok())).isEqualTo("{\"success\":true}");
        assertThat(Json.write(new SuccessResponse(false, ""))).isEqualTo("{\"success\":false}");
        assertThat(Json.write(new IdInput("x"))).isEqualTo("{\"id\":\"x\"}");
    }
}
