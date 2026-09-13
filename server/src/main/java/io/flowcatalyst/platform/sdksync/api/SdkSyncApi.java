package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.docs.AppDocRepository;
import io.flowcatalyst.platform.docs.operations.SyncAppDocs;
import io.flowcatalyst.platform.dispatchpool.operations.SyncDispatchPools;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.eventtype.operations.SyncEventTypes;
import io.flowcatalyst.platform.openapispecs.OpenApiSpecRepository;
import io.flowcatalyst.platform.openapispecs.operations.SyncOpenApiSpec;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.operations.SyncPrincipals;
import io.flowcatalyst.platform.process.ProcessRepository;
import io.flowcatalyst.platform.process.operations.SyncProcesses;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.role.operations.SyncRoles;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.operations.SyncScheduledJobs;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.operations.SyncSubscriptions;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;

import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.*;

/// The declarative self-registration surface an application's SDK calls at
/// boot (`docs/spec/sdksync.md`) — ten `POST` routes under
/// `/api/applications/{appCode}`, plus one body-scoped alias.
///
/// ### This unit owns no aggregate and no operation
///
/// Every handler is the same four steps: **coarse permission → resolve
/// `{appCode}` → command → run → response**. There are no business rules here.
/// Defaults (`concurrency ⇒ 10`, `timezone ⇒ UTC`, `deliveryMaxAttempts ⇒ 3`)
/// belong to the aggregates — CONVENTIONS' "defaults are domain, not
/// transport" — and the DTOs carry the wire shape verbatim with `null`
/// meaning absent. The single exception is documented on
/// [SyncPrincipalsRequest.Input#active], where the command field is a
/// primitive and the transport is the only place an absent value can be seen.
///
/// ### Where the per-application check happens, and why it varies
///
/// "May this caller sync *this* application?" is applied **exactly once per
/// route**, but in one of two places:
///
///   - in the owning operation's `authorize` phase, when the command carries
///     an `applicationId`;
///   - **here**, right after resolution, for event-types and principals —
///     their commands are keyed by code alone, so there is no id for the
///     operation to check.
///
/// Getting this wrong in the second direction is the dangerous one: with
/// `removeUnlisted` a sync **prunes**, so a route that reached an application
/// the caller is not bound to would delete another tenant's rows.
///
/// ### `removeUnlisted`
///
/// A **query** parameter, and `true` only for the literal string — absent or
/// anything else is `false`. It is deliberately not in the body: it is an
/// instruction about how to apply the payload, not part of it. The scheduled
/// jobs route is the exception, carrying `archiveUnlisted` in its body; that
/// is the lockfile's shape, not a choice made here.
public final class SdkSyncApi {

    private SdkSyncApi() {
    }

    /// The repositories are the same instances the aggregates' own APIs use —
    /// this surface assembles their commands, it does not own their state.
    public record State(ApplicationRepository apps, EventTypeRepository eventTypes, RoleRepository roles,
                        SubscriptionRepository subscriptions, ConnectionRepository connections,
                        ProcessRepository processes, DispatchPoolRepository dispatchPools,
                        ScheduledJobRepository scheduledJobs, OpenApiSpecRepository specs,
                        AppDocRepository appDocs, PrincipalRepository principals, UnitOfWork uow) {

        public State {
            Objects.requireNonNull(apps, "apps");
            Objects.requireNonNull(uow, "uow");
        }
    }

    public static void register(Routes routes, State s) {
        Routes write = routes.in(Group.API_WRITE);
        write.post("/api/applications/{appCode}/event-types/sync", Auth.scoped(ctx -> syncEventTypes(ctx, s)));
        write.post("/api/applications/{appCode}/roles/sync", Auth.scoped(ctx -> syncRoles(ctx, s)));
        write.post("/api/applications/{appCode}/subscriptions/sync", Auth.scoped(ctx -> syncSubscriptions(ctx, s)));
        write.post("/api/applications/{appCode}/dispatch-pools/sync", Auth.scoped(ctx -> syncDispatchPools(ctx, s)));
        write.post("/api/applications/{appCode}/principals/sync", Auth.scoped(ctx -> syncPrincipals(ctx, s)));
        write.post("/api/applications/{appCode}/docs/sync", Auth.scoped(ctx -> syncDocs(ctx, s)));
        write.post("/api/applications/{appCode}/processes/sync", Auth.scoped(ctx -> syncProcesses(ctx, s)));
        write.post("/api/processes/sync", Auth.scoped(ctx -> syncProcessesByBody(ctx, s)));
        write.post("/api/applications/{appCode}/scheduled-jobs/sync", Auth.scoped(ctx -> syncScheduledJobs(ctx, s)));
        write.post("/api/applications/{appCode}/openapi/sync", Auth.scoped(ctx -> syncOpenapi(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void syncEventTypes(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), EVENT_TYPE_SYNC, EVENT_TYPE_MANAGE,
                APP_SVC_EVENT_TYPE_CREATE, APP_SVC_EVENT_TYPE_UPDATE, APP_SVC_EVENT_TYPE_DELETE);
        var app = application(ctx, s);
        // Checked here: SyncEventTypesCommand is keyed by code, so the
        // operation has no id to authorise against — and it is `publicAccess`
        // besides, because the BFF catalogue sync shares it.
        Checks.checkApplicationAccess(Auth.current(), app.id(), app.code());
        var cmd = ctx.bodyAsClass(SyncEventTypesRequest.class).toCommand(app.code(), removeUnlisted(ctx));
        ctx.json(SyncResultResponse.from(SyncEventTypes.of(s.eventTypes()).run(s.uow(), cmd, Auth.executionContext())));
    }

    private static void syncRoles(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), ROLE_MANAGE, ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE,
                APP_SVC_ROLE_CREATE, APP_SVC_ROLE_UPDATE, APP_SVC_ROLE_DELETE);
        var app = application(ctx, s);
        var cmd = ctx.bodyAsClass(SyncRolesRequest.class).toCommand(app.code(), app.id(), removeUnlisted(ctx));
        ctx.json(SyncResultResponse.from(SyncRoles.of(s.roles()).run(s.uow(), cmd, Auth.executionContext())));
    }

    private static void syncSubscriptions(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), SUBSCRIPTION_SYNC, SUBSCRIPTION_MANAGE,
                APP_SVC_SUBSCRIPTION_CREATE, APP_SVC_SUBSCRIPTION_UPDATE, APP_SVC_SUBSCRIPTION_DELETE);
        var app = application(ctx, s);
        var cmd = ctx.bodyAsClass(SyncSubscriptionsRequest.class).toCommand(app.id(), app.code(), removeUnlisted(ctx));
        var op = SyncSubscriptions.of(s.subscriptions(), s.connections(), s.dispatchPools());
        ctx.json(SyncResultResponse.from(op.run(s.uow(), cmd, Auth.executionContext())));
    }

    private static void syncDispatchPools(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), DISPATCH_POOL_SYNC, DISPATCH_POOL_MANAGE);
        var app = application(ctx, s);
        var cmd = ctx.bodyAsClass(SyncDispatchPoolsRequest.class).toCommand(app.id(), app.code(), removeUnlisted(ctx));
        ctx.json(SyncResultResponse.from(SyncDispatchPools.of(s.dispatchPools()).run(s.uow(), cmd, Auth.executionContext())));
    }

    private static void syncPrincipals(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), USER_MANAGE, USER_CREATE, USER_UPDATE, USER_DELETE, USER_ASSIGN_ROLES);
        var app = application(ctx, s);
        // As for event-types: the command carries no applicationId.
        Checks.checkApplicationAccess(Auth.current(), app.id(), app.code());
        var cmd = ctx.bodyAsClass(SyncPrincipalsRequest.class).toCommand(app.code(), removeUnlisted(ctx));
        ctx.json(SyncResultResponse.from(SyncPrincipals.of(s.principals()).run(s.uow(), cmd, Auth.executionContext())));
    }

    private static void syncDocs(Exchange ctx, State s) {
        Checks.require(Auth.current(), APP_SVC_DOCS_SYNC);
        var app = application(ctx, s);
        var cmd = ctx.bodyAsClass(SyncDocsRequest.class).toCommand(app.id(), app.code());
        // Docs emit no rollup event — the store's replace result is the answer.
        var result = SyncAppDocs.of(s.appDocs()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(SyncResultResponse.from(app.code(), result));
    }

    private static void syncProcesses(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), PROCESS_SYNC, APP_SVC_PROCESS_SYNC);
        var app = application(ctx, s);
        var cmd = ctx.bodyAsClass(SyncProcessesRequest.class).toCommand(app.code(), app.id(), removeUnlisted(ctx));
        ctx.json(SyncResultResponse.from(SyncProcesses.of(s.processes()).run(s.uow(), cmd, Auth.executionContext())));
    }

    /// The Laravel-SDK alias: same handling, application code from the body.
    private static void syncProcessesByBody(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), PROCESS_SYNC, APP_SVC_PROCESS_SYNC);
        var body = ctx.bodyAsClass(SyncProcessesByBodyRequest.class);
        var app = application(body.applicationCode(), s);
        var cmd = body.toCommand(app.code(), app.id(), removeUnlisted(ctx));
        ctx.json(SyncResultResponse.from(SyncProcesses.of(s.processes()).run(s.uow(), cmd, Auth.executionContext())));
    }

    private static void syncScheduledJobs(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), APP_SVC_SCHEDULED_JOB_SYNC, SCHEDULED_JOB_SYNC, SCHEDULED_JOB_MANAGE);
        var app = application(ctx, s);
        // archiveUnlisted travels in the body here, not as the query flag.
        var cmd = ctx.bodyAsClass(SyncScheduledJobsRequest.class).toCommand(app.code(), app.id());
        var event = SyncScheduledJobs.of(s.scheduledJobs()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(SyncScheduledJobsResultResponse.from(event));
    }

    private static void syncOpenapi(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), APPLICATION_OPENAPI_SYNC, APPLICATION_OPENAPI_MANAGE);
        var app = application(ctx, s);
        var cmd = ctx.bodyAsClass(SyncOpenapiRequest.class).toCommand(app.id(), app.code());
        ctx.json(SyncOpenApiSpecResponse.from(SyncOpenApiSpec.of(s.specs()).run(s.uow(), cmd, Auth.executionContext())));
    }

    // ── Shared steps ───────────────────────────────────────────────────────

    private static Application application(Exchange ctx, State s) {
        return application(ctx.pathParam("appCode"), s);
    }

    /// Resolves the application **by code**, and hands the commands the stored
    /// `id` and `code` rather than the raw path segment — so a command can
    /// never carry a code the store did not confirm.
    private static Application application(String code, State s) {
        return s.apps().findByCode(code == null ? "" : code)
                .orElseThrow(() -> HttpError.notFound("Application", code == null ? "" : code));
    }

    /// `true` only for the literal `true`; absent or anything else is `false`.
    ///
    /// Deliberately not lenient. This flag decides whether a sync **deletes**
    /// rows, so a typo like `?removeUnlisted=1` must read as "no" — the safe
    /// direction is the one that keeps data.
    private static boolean removeUnlisted(Exchange ctx) {
        return "true".equals(ctx.queryParam("removeUnlisted"));
    }
}
