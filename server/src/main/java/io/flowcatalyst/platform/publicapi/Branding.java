package io.flowcatalyst.platform.publicapi;

import io.flowcatalyst.platform.platformconfig.ConfigCoordinate;
import io.flowcatalyst.platform.platformconfig.PlatformConfig;
import io.flowcatalyst.platform.platformconfig.PlatformConfigRepository;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/// The configurable brand (spec `docs/spec/publicapi.md` §4–6): the platform
/// name a user sees in the SPA, in emails, in their authenticator app and in
/// passkey prompts, and the login / email theme. Each read goes back to
/// `app_platform_configs` (no caching — a change takes effect without a
/// restart) and falls back to the defaults whenever the value is unset,
/// blank, malformed or unavailable, so every caller stays unconditional and
/// the login page renders out of the box (spec §1, open question 3).
public final class Branding {

    private static final Logger LOG = LoggerFactory.getLogger(Branding.class);

    /// The brand when nothing is configured (spec §4).
    public static final String DEFAULT_PLATFORM_NAME = "Flowcatalyst";
    /// Where the platform name lives (spec §4).
    public static final ConfigCoordinate PLATFORM_NAME = ConfigCoordinate.global("platform", "branding", "platform-name");
    /// Where the login theme lives — the row the admin settings page writes (spec §5).
    public static final ConfigCoordinate LOGIN_THEME = ConfigCoordinate.global("platform", "login", "theme");

    private final PlatformConfigRepository configs;

    public Branding(PlatformConfigRepository configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /// The configured platform name, trimmed; [#DEFAULT_PLATFORM_NAME] when
    /// unset, blank or unavailable (spec §4 table).
    public String platformName() {
        return value(PLATFORM_NAME)
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .orElse(DEFAULT_PLATFORM_NAME);
    }

    /// The stored login theme as stored; [LoginTheme#EMPTY] when unset, blank,
    /// malformed or unavailable (spec §5 table). Malformed JSON is logged —
    /// it means the admin page and this reader disagree.
    public LoginTheme loginTheme() {
        Optional<String> stored = value(LOGIN_THEME).filter(v -> !v.isBlank());
        if (stored.isEmpty()) return LoginTheme.EMPTY;
        return LoginTheme.parse(stored.get()).orElseGet(() -> {
            LOG.warn("branding: stored login theme at {} is not a JSON object; using defaults", LOGIN_THEME.path());
            return LoginTheme.EMPTY;
        });
    }

    /// The email theme: the stored login theme layered over the defaults,
    /// brand name defaulting to [#platformName] (spec §6).
    public EmailTheme emailTheme() {
        return EmailTheme.of(platformName(), loginTheme());
    }

    /// The raw stored value at `c`; empty when there is no row. A failed
    /// lookup is logged and reads as "no row" (spec §1, open question 3): a
    /// DB outage must not take the login page's branding down with it.
    private Optional<String> value(ConfigCoordinate c) {
        try {
            return configs.findByCoordinate(c).map(PlatformConfig::value);
        } catch (DataAccessException e) {
            LOG.warn("branding: lookup of {} failed; using defaults", c.path(), e);
            return Optional.empty();
        }
    }
}
