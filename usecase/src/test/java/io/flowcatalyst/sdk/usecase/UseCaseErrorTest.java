package io.flowcatalyst.sdk.usecase;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UseCaseErrorTest {

    @Test
    void kindsMapToHttpStatusLikeTheGoImplementation() {
        assertThat(UseCaseError.validation("C", "m").httpStatus()).isEqualTo(400);
        assertThat(UseCaseError.authorization("C", "m").httpStatus()).isEqualTo(403);
        assertThat(UseCaseError.notFound("C", "m").httpStatus()).isEqualTo(404);
        assertThat(UseCaseError.businessRule("C", "m").httpStatus()).isEqualTo(409);
        assertThat(UseCaseError.conflict("C", "m").httpStatus()).isEqualTo(409);
        assertThat(UseCaseError.internal("C", "m", null).httpStatus()).isEqualTo(500);
    }

    @Test
    void kindNamesMatchTheWireVocabulary() {
        assertThat(UseCaseError.validation("C", "m").kind()).isEqualTo("validation");
        assertThat(UseCaseError.businessRule("C", "m").kind()).isEqualTo("business_rule");
        assertThat(UseCaseError.authorization("C", "m").kind()).isEqualTo("authorization");
        assertThat(UseCaseError.notFound("C", "m").kind()).isEqualTo("not_found");
        assertThat(UseCaseError.conflict("C", "m").kind()).isEqualTo("conflict");
        assertThat(UseCaseError.internal("C", "m", null).kind()).isEqualTo("internal");
    }

    @Test
    void detailsAreCopiedAndNullTolerant() {
        var details = new HashMap<String, Object>();
        details.put("field", "code");
        details.put("hint", null);
        UseCaseError e = UseCaseError.validation("CODE_REQUIRED", "code required").withDetails(details);
        details.put("field", "mutated");
        assertThat(e.details()).containsEntry("field", "code").containsKey("hint");
        assertThat(e).isInstanceOf(UseCaseError.Validation.class);
    }

    @Test
    void exceptionRendersLikeGoErrorAndCarriesCause() {
        var cause = new IllegalStateException("boom");
        var ex = UseCaseException.internal("TX_BEGIN", "could not open transaction", cause);
        assertThat(ex.getMessage()).isEqualTo("internal: TX_BEGIN: could not open transaction: java.lang.IllegalStateException: boom");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.httpStatus()).isEqualTo(500);

        var plain = UseCaseException.conflict("CODE_EXISTS", "exists");
        assertThat(plain.getMessage()).isEqualTo("conflict: CODE_EXISTS: exists");
        assertThat(plain.getCause()).isNull();
    }

    @Test
    void findWalksTheCauseChain() {
        var inner = UseCaseException.notFound("EVENT_TYPE_NOT_FOUND", "nope");
        var wrapped = new RuntimeException("outer", new RuntimeException("middle", inner));
        assertThat(UseCaseException.find(wrapped)).contains(inner.error());
        assertThat(UseCaseException.find(new RuntimeException("x"))).isEmpty();
    }

    @Test
    void exhaustiveSwitchOverKinds() {
        UseCaseError e = UseCaseError.conflict("C", "m");
        String label = switch (e) {
            case UseCaseError.Validation v -> "v:" + v.code();
            case UseCaseError.BusinessRule b -> "b:" + b.code();
            case UseCaseError.Authorization a -> "a:" + a.code();
            case UseCaseError.NotFound n -> "n:" + n.code();
            case UseCaseError.Conflict c -> "c:" + c.code();
            case UseCaseError.Internal i -> "i:" + i.code();
        };
        assertThat(label).isEqualTo("c:C");
        assertThat(e.details()).isEqualTo(Map.of());
    }
}
