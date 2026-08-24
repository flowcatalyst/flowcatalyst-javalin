package io.flowcatalyst.platform.principal;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Objects;

import static io.flowcatalyst.db.generated.Tables.TNT_ANCHOR_DOMAINS;

/// The anchor-domain register (`tnt_anchor_domains`): an email domain
/// whose users may be created as `ANCHOR` (spec §7, §10). The register is
/// owned by the auth aggregate, which has not been ported; this is the one
/// read this package needs, to be replaced by that aggregate's repository.
public interface AnchorDomains {

    /// Whether `domain` (compared lower-cased) is a registered anchor domain.
    boolean contains(String domain);

    /// The database-backed register.
    static AnchorDomains inDatabase(DataSource dataSource) {
        DSLContext dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
        return domain -> dsl.fetchExists(dsl.selectOne().from(TNT_ANCHOR_DOMAINS)
                .where(TNT_ANCHOR_DOMAINS.DOMAIN.eq(domain.toLowerCase(Locale.ROOT))));
    }

    /// No anchor domains at all (tests, minimal wiring).
    static AnchorDomains none() {
        return _ -> false;
    }
}
