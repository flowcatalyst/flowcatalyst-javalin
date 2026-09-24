package io.flowcatalyst.platform.serviceaccount;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.sdk.result.Result;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// Who may cause a delivery to go out under a service account's webhook
/// credentials (`docs/spec/security-fixes-2026-09-24.md` S3.1/S3.2). A caller
/// that names the account a subscription or connection signs with, or that
/// ingests a job some account will sign, chooses where that account's bearer
/// token and signature go — so it must already hold everything the account
/// can reach. The rule, in order:
///
/// 1. a super-admin (`platform:*:*:*`) may use any account;
/// 2. an account that belongs to an application may be used by that
///    application itself (the caller is one of its service accounts) — and
///    by nobody else. Not even on a subscription that application synced: its
///    endpoint is editable by whoever administers the subscription's client,
///    so "the configuration is the application's" would let that admin point
///    the application's credentials at their own endpoint;
/// 3. an application's own credentials (the caller IS an application) may
///    use no other account at all — application service accounts are
///    anchor-tier (`service-account-reach.md` §1), so tenancy alone would
///    let one application borrow any tenant's or the operator's account,
///    and the trust model (owner, 2026-09-23) trusts application
///    credentials with neither;
/// 4. otherwise the caller's tenancy must cover the account's: an account
///    with no client links is anchor-tier (`service-account-reach.md` §1), so
///    only an anchor covers it; an account linked to clients is covered only
///    when the caller can access **every** one of them — a partner account
///    shared by clients A and B signs deliveries B's receivers accept, so a
///    caller confined to A must not be able to borrow it.
///
/// The application an account belongs to is [ServiceAccount#applicationId].
/// The application a caller *is* is found through its principal: a
/// `SERVICE` principal's linked account's `applicationId`. A user, or a
/// standalone account with no application, is no application.
///
/// Every lookup is allowed to throw straight through — a database failure
/// is infrastructure, answered 500 by the transport, never a silent allow.
public final class SigningReach {

    /// Why a caller may not use a signing identity. Every case states its own
    /// [#message], the text the 403 carries.
    public sealed interface Refusal {
        String message();

        /// The account reaches tenants the caller does not.
        record OutOfReach(String serviceAccountCode) implements Refusal {
            @Override
            public String message() {
                return "service account " + serviceAccountCode + " reaches clients the caller cannot access";
            }
        }

        /// The account belongs to an application the caller is not.
        record OtherApplication(String serviceAccountCode) implements Refusal {
            @Override
            public String message() {
                return "service account " + serviceAccountCode + " belongs to an application the caller is not";
            }
        }

        /// The caller is an application, and the account is not one of its own.
        record ApplicationCaller(String serviceAccountCode) implements Refusal {
            @Override
            public String message() {
                return "service account " + serviceAccountCode
                        + " is not the calling application's own; an application signs only with its own accounts";
            }
        }

        /// A job would be signed by an application's own account and the
        /// caller is not that application.
        record NotTheApplication(String applicationCode) implements Refusal {
            @Override
            public String message() {
                return "application " + applicationCode + " signs only its own jobs; the caller is not that application";
            }
        }
    }

    /// The one [ServiceAccountRepository] read this needs, as a seam.
    @FunctionalInterface
    public interface ServiceAccountLookup {
        Optional<ServiceAccount> findById(String id);
    }

    /// Principal id → the service-account id it is linked to, empty for a
    /// user or an unknown principal.
    @FunctionalInterface
    public interface PrincipalAccountLookup {
        Optional<String> serviceAccountIdOf(String principalId);
    }

    /// Application code → id.
    @FunctionalInterface
    public interface ApplicationIdLookup {
        Optional<String> idOf(String applicationCode);
    }

    private final ServiceAccountLookup accounts;
    private final PrincipalAccountLookup principals;
    private final ApplicationIdLookup applications;

    public SigningReach(ServiceAccountLookup accounts, PrincipalAccountLookup principals, ApplicationIdLookup applications) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.applications = Objects.requireNonNull(applications, "applications");
    }

    public static SigningReach of(ServiceAccountRepository accounts, PrincipalRepository principals,
                                  ApplicationRepository applications) {
        // The same resolution the delivery signer uses — a reference this reads as
        // dangling must never be one the signer signs with.
        return new SigningReach(accounts::findByIdOrServicePrincipalId,
                principalId -> principals.findById(principalId).map(Principal::serviceAccountId),
                code -> applications.findByCode(code).map(Application::id));
    }

    /// A copy whose lookups are memoised, for ONE request: a batch of a
    /// thousand jobs resolves each account, application and the caller's own
    /// linkage once. Not thread-safe, and never kept past the request — a
    /// memo that outlived it would go on answering for an account since
    /// deactivated or re-linked.
    public SigningReach perRequest() {
        Map<String, Optional<ServiceAccount>> accountMemo = new HashMap<>();
        Map<String, Optional<String>> principalMemo = new HashMap<>();
        Map<String, Optional<String>> applicationMemo = new HashMap<>();
        return new SigningReach(
                id -> accountMemo.computeIfAbsent(id, accounts::findById),
                principalId -> principalMemo.computeIfAbsent(principalId, principals::serviceAccountIdOf),
                code -> applicationMemo.computeIfAbsent(code, applications::idOf));
    }

    /// The service account `id`, if it exists.
    public Optional<ServiceAccount> account(String id) {
        return accounts.findById(id);
    }

    /// The id of application `code`, if it exists.
    public Optional<String> applicationId(String code) {
        return applications.idOf(code);
    }

    /// Whether `ac` may send deliveries signed by `account`.
    public Result<ServiceAccount, Refusal> mayUse(AuthContext ac, ServiceAccount account) {
        Objects.requireNonNull(ac, "ac");
        if (ac.isSuperAdmin()) {
            return Result.ok(account);
        }
        String callerApplication = callerApplicationId(ac);
        String owningApplication = blankToNull(account.applicationId());
        if (owningApplication != null) {
            if (owningApplication.equals(callerApplication)) {
                return Result.ok(account);
            }
            return Result.err(new Refusal.OtherApplication(account.code()));
        }
        if (callerApplication != null) {
            return Result.err(new Refusal.ApplicationCaller(account.code()));
        }
        boolean covered = account.clientIds().isEmpty()
                ? ac.isAnchor()
                : account.clientIds().stream().allMatch(ac::canAccessClient);
        return covered ? Result.ok(account) : Result.err(new Refusal.OutOfReach(account.code()));
    }

    /// Whether `ac` may send deliveries signed by application
    /// `applicationId`'s own account (the resolver's step 3, `DeliveryCredentials`):
    /// only that application itself, or a super-admin.
    public Result<String, Refusal> mayUseApplication(AuthContext ac, String applicationId, String applicationCode) {
        Objects.requireNonNull(ac, "ac");
        if (ac.isSuperAdmin() || applicationId.equals(callerApplicationId(ac))) {
            return Result.ok(applicationId);
        }
        return Result.err(new Refusal.NotTheApplication(applicationCode));
    }

    /// The application the caller acts as, or `null`.
    private String callerApplicationId(AuthContext ac) {
        return principals.serviceAccountIdOf(ac.principalId())
                .flatMap(accounts::findById)
                .map(ServiceAccount::applicationId)
                .map(SigningReach::blankToNull)
                .orElse(null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
