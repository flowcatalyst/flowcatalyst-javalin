package io.flowcatalyst.platform.serviceaccount;

import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// `app_applications.service_account_id` holds the service PRINCIPAL's id, and a
/// connection written by an application's sync copies it — so a stored "service
/// account id" can be either kind. The delivery signer and the signing-reach check
/// must both resolve it to the account: before, the signer found nothing (sync-written
/// connections delivered unsigned) and the reach check read it as dangling (allowed).
/// Mutant: resolve by account id only — both assertions fail.
class ServicePrincipalIdResolutionTest {

    private static final DataSource DS = TestPg.dataSource();

    @Test
    void aStoredServicePrincipalIdResolvesToItsAccountForTheSignerAndTheReachCheck() {
        String account = SigningAccounts.seed(DS, List.of(), io.flowcatalyst.platform.shared.tsid.EntityType.APPLICATION.generate());
        String principal = SigningAccounts.servicePrincipal(DS, account);
        var repo = new ServiceAccountRepository(DS, Optional.empty());

        assertThat(OutboundCredentials.resolveById(repo, principal))
                .as("the signer finds the account behind the principal id (it has no credentials seeded)")
                .isInstanceOf(OutboundCredentials.ById.NoCredentials.class);
        assertThat(SigningAccounts.reach(DS).account(principal).map(ServiceAccount::id))
                .as("the reach check sees the same account, never a dangling reference")
                .contains(account);
        assertThat(OutboundCredentials.resolveById(repo, io.flowcatalyst.platform.shared.tsid.EntityType.PRINCIPAL.generate()))
                .isInstanceOf(OutboundCredentials.ById.Missing.class);
    }
}
