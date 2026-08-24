package io.flowcatalyst.platform.principal;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// The single password-acceptance policy for internal (password-auth) users
/// (spec §4.1): NIST SP 800-63B-shaped — length bounds, a common-password
/// blocklist and "not your own identity" — with **no** composition rules.
/// Applied wherever this package sets a password from plaintext; the relaxed
/// SDK reset and sync's verbatim hashes bypass it by contract.
///
/// The check is a verification boundary whose negative outcome is routine,
/// so it returns a sealed [Verdict] the caller switches on rather than
/// throwing. Lives here until the auth subsystem lands, then moves to
/// `shared.auth` with the login flows that share it.
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128;

    /// Accepted, or rejected with the pinned code + user-safe message.
    public sealed interface Verdict permits Accepted, Rejected {
        /// Throws the rejection as a validation error; a no-op when accepted.
        default void require() {
            if (this instanceof Rejected r) throw UseCaseException.validation(r.code(), r.message());
        }
    }

    public record Accepted() implements Verdict {
    }

    public record Rejected(String code, String message) implements Verdict {
    }

    private static final Verdict ACCEPTED = new Accepted();

    private PasswordPolicy() {
    }

    /// Checks `password` for the account identified by `email` and `name`
    /// (either may be `null` / blank when unknown). First failing rule wins,
    /// in the spec's order.
    public static Verdict check(String password, String email, String name) {
        String pw = password == null ? "" : password;
        if (pw.length() < MIN_LENGTH) {
            return new Rejected("PASSWORD_TOO_SHORT", "Password must be at least " + MIN_LENGTH + " characters");
        }
        if (pw.length() > MAX_LENGTH) {
            return new Rejected("PASSWORD_TOO_LONG", "Password must be at most " + MAX_LENGTH + " characters");
        }
        String norm = pw.trim().toLowerCase(Locale.ROOT);
        if (allSameCodePoint(norm)) {
            return new Rejected("PASSWORD_TOO_WEAK", "Password cannot be a single repeated character");
        }
        if (containsIdentity(norm, email, name)) {
            return new Rejected("PASSWORD_CONTAINS_IDENTITY", "Password cannot contain your email address or name");
        }
        if (CommonPasswords.SET.contains(norm)) {
            return new Rejected("PASSWORD_TOO_COMMON",
                    "That password is on the list of most commonly used passwords — choose something less guessable");
        }
        if (norm.contains("flowcatalyst")) {
            return new Rejected("PASSWORD_TOO_COMMON", "Password cannot be based on the product name");
        }
        return ACCEPTED;
    }

    /// The full email, its local part and each whitespace-separated word of
    /// the name must not appear in the password, forwards or reversed; tokens
    /// shorter than four characters only match on exact equality (containment
    /// would forbid "jo" inside "majority").
    private static boolean containsIdentity(String normPw, String email, String name) {
        String reversed = new StringBuilder(normPw).reverse().toString();
        var candidates = new ArrayList<String>(6);
        String e = EmailAddress.normalise(email);
        if (!e.isEmpty()) {
            candidates.add(e);
            int at = e.indexOf('@');
            if (at > 0) candidates.add(e.substring(0, at));
        }
        if (name != null) {
            for (String word : name.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
                if (!word.isEmpty()) candidates.add(word);
            }
        }
        for (String c : candidates) {
            boolean hit = c.length() >= 4
                    ? normPw.contains(c) || reversed.contains(c)
                    : normPw.equals(c) || reversed.equals(c);
            if (hit) return true;
        }
        return false;
    }

    private static boolean allSameCodePoint(String s) {
        return s.codePoints().distinct().count() <= 1;
    }

    /// The embedded 10k most-common list (SecLists, lower-cased), loaded once.
    private static final class CommonPasswords {
        static final Set<String> SET = load();

        private static Set<String> load() {
            try (InputStream in = PasswordPolicy.class.getResourceAsStream("common_passwords.txt")) {
                if (in == null) throw new IllegalStateException("common_passwords.txt missing from the classpath");
                var set = new HashSet<String>(16_384);
                try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    List<String> lines = reader.lines().toList();
                    for (String line : lines) {
                        String w = line.trim();
                        if (!w.isEmpty()) set.add(w);
                    }
                }
                return Set.copyOf(set);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
