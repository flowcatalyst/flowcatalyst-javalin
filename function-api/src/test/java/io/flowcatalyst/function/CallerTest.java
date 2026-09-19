package io.flowcatalyst.function;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `docs/spec/function-invocation.md` §7: `Caller`'s three cases, and
/// `Principal`'s own invariants (required `id`/`type`, defensive copy of
/// `permissions`).
class CallerTest {

    @Test
    void platformAndAnonymousAreSharedInstances() {
        assertThat(Caller.Platform.INSTANCE).isInstanceOf(Caller.class);
        assertThat(Caller.Anonymous.INSTANCE).isInstanceOf(Caller.class);
        assertThat(new Caller.Platform()).isEqualTo(Caller.Platform.INSTANCE);
        assertThat(new Caller.Anonymous()).isEqualTo(Caller.Anonymous.INSTANCE);
    }

    @Test
    void principalRequiresIdAndType() {
        assertThatThrownBy(() -> new Caller.Principal(null, "user", null, Set.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Caller.Principal("id", null, null, Set.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void principalClientIdMayBeNull() {
        Caller.Principal principal = new Caller.Principal("id", "service-account", null, Set.of("read"));
        assertThat(principal.clientId()).isNull();
    }

    @Test
    void principalPermissionsAreIndependentOfTheSetPassedIn() {
        Set<String> permissions = new HashSet<>(Set.of("read"));
        Caller.Principal principal = new Caller.Principal("id", "user", "client-1", permissions);
        permissions.add("write");
        assertThat(principal.permissions()).containsExactly("read");
    }

    @Test
    void principalPermissionsAreUnmodifiable() {
        Caller.Principal principal = new Caller.Principal("id", "user", null, Set.of("read"));
        assertThatThrownBy(() -> principal.permissions().add("write"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
