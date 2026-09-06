package io.flowcatalyst.platform.shared.apicommon;

import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageQueryTest {

    static TestHttp http;

    @BeforeAll
    static void start() {
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.get("/q", ctx -> {
                var q = PageQuery.from(ctx);
                ctx.result(q.pageIndex() + "/" + q.pageSize() + "/" + q.offset() + "/" + q.limit());
            });
        });
    }

    @AfterAll
    static void stop() {
        http.close();
    }

    @Test
    void defaults() {
        assertThat(http.get("/q").body()).isEqualTo("0/20/0/20");
    }

    @Test
    void pageAndSize() {
        assertThat(http.get("/q?page=3&size=50").body()).isEqualTo("3/50/150/50");
    }

    @Test
    void negativePageClampsToZero() {
        assertThat(http.get("/q?page=-4").body()).isEqualTo("0/20/0/20");
    }

    @Test
    void aliasesInPriorityOrder() {
        assertThat(http.get("/q?limit=7").body()).isEqualTo("0/7/0/7");
        assertThat(http.get("/q?pageSize=8").body()).isEqualTo("0/8/0/8");
        assertThat(http.get("/q?page_size=9").body()).isEqualTo("0/9/0/9");
        assertThat(http.get("/q?size=5&limit=7&pageSize=8&page_size=9").body()).isEqualTo("0/5/0/5");
        assertThat(http.get("/q?limit=7&pageSize=8&page_size=9").body()).isEqualTo("0/7/0/7");
        assertThat(http.get("/q?pageSize=8&page_size=9").body()).isEqualTo("0/8/0/8");
        // a zero/negative alias is "absent", the next one wins
        assertThat(http.get("/q?size=0&limit=-1&pageSize=8").body()).isEqualTo("0/8/0/8");
    }

    @Test
    void capsAtMaxPageSize() {
        assertThat(http.get("/q?size=10000000").body()).isEqualTo("0/1000/0/1000");
        assertThat(http.get("/q?limit=1001").body()).isEqualTo("0/1000/0/1000");
        assertThat(PageQuery.MAX_PAGE_SIZE).isEqualTo(1000);
    }

    @Test
    void nonIntegerIsAValidationEnvelope() {
        var r = http.get("/q?page=abc");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).isEqualTo("{\"error\":\"VALIDATION\",\"message\":\"validation failed\","
                + "\"details\":{\"errors\":[{\"message\":\"invalid integer\",\"location\":\"query.page\",\"value\":\"abc\"}]}}\n");
    }

    @Test
    void pureRecordResolution() {
        assertThat(new PageQuery(2, 0, 0, 30, 0).pageSize()).isEqualTo(30);
        assertThat(new PageQuery(2, 0, 0, 30, 0).offset()).isEqualTo(60);
        assertThat(PageQuery.DEFAULT.pageSize()).isEqualTo(20);
        assertThat(new PageQuery(0, 25).limit()).isEqualTo(25);
    }
}
