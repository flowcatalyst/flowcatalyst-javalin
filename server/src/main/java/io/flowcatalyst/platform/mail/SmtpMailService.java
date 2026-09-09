package io.flowcatalyst.platform.mail;

import io.flowcatalyst.server.EnvReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/// SMTP delivery (`docs/spec/auth-identity.md` §9): `FC_SMTP_*` wins over
/// the bare `SMTP_*` name for every setting; port 587 and
/// `noreply@flowcatalyst.local` by default; `SECURE` ∈ {true, 1, yes, on}
/// (any case) means implicit TLS, otherwise plain with STARTTLS when the
/// server offers it. One recipient per message.
public final class SmtpMailService implements MailService {

    private static final Logger LOG = LoggerFactory.getLogger(SmtpMailService.class);

    public record Config(String host, int port, String username, String password, String from, boolean secure) {
        public Config {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(from, "from");
        }

        public static Optional<Config> fromEnv(EnvReader env) {
            String host = env.firstSet("FC_SMTP_HOST", "SMTP_HOST").map(String::trim).orElse("");
            if (host.isEmpty()) {
                return Optional.empty();
            }
            int port = env.firstSet("FC_SMTP_PORT", "SMTP_PORT").map(String::trim).map(Integer::parseInt).orElse(587);
            String username = env.firstSet("FC_SMTP_USERNAME", "SMTP_USERNAME").map(String::trim).orElse("");
            String password = env.firstSet("FC_SMTP_PASSWORD", "SMTP_PASSWORD").orElse("");
            String from = env.firstSet("FC_SMTP_FROM", "SMTP_FROM").map(String::trim).orElse("noreply@flowcatalyst.local");
            boolean secure = switch (env.firstSet("FC_SMTP_SECURE", "SMTP_SECURE").map(String::trim).orElse("").toLowerCase(Locale.ROOT)) {
                case "true", "1", "yes", "on" -> true;
                default -> false;
            };
            var c = new Config(host, port, username, password, from, secure);
            LOG.atInfo().setMessage("SMTP email service configured")
                    .addKeyValue("host", host)
                    .addKeyValue("port", port)
                    .addKeyValue("from", from)
                    .addKeyValue("secure", secure)
                    .log();
            return Optional.of(c);
        }
    }

    private final Config config;
    private final Clock clock;

    public SmtpMailService(Config config) {
        this(config, Clock.systemUTC());
    }

    public SmtpMailService(Config config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void send(Mail mail) {
        String raw = Mime.build(config.from(), mail, clock.instant().atZone(ZoneOffset.UTC), messageIdHost());
        try (SmtpClient c = SmtpClient.connect(config.host(), config.port(), config.secure())) {
            c.ehlo("localhost");
            if (!config.secure() && c.supports("STARTTLS")) {
                c.startTls("localhost");
            }
            if (config.username() != null && !config.username().isEmpty()) {
                c.authPlain(config.username(), config.password() == null ? "" : config.password());
            }
            c.mail(config.from(), mail.to(), raw);
            c.quit();
        } catch (IOException e) {
            throw new MailException("smtp send to " + config.host() + ":" + config.port() + " failed: " + e.getMessage(), e);
        }
    }

    private String messageIdHost() {
        int at = config.from().lastIndexOf('@');
        return at < 0 || at == config.from().length() - 1 ? "flowcatalyst.local" : config.from().substring(at + 1);
    }
}
