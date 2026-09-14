<?php

declare(strict_types=1);

namespace FlowCatalyst\Sync;

use FlowCatalyst\Client\FlowCatalystClient;
use FlowCatalyst\DTOs\EventTypeBinding;
use FlowCatalyst\DTOs\Requests\SyncDispatchPoolEntry;
use FlowCatalyst\DTOs\Requests\SyncEventTypeEntry;
use FlowCatalyst\DTOs\Requests\SyncPrincipalEntry;
use FlowCatalyst\DTOs\Requests\SyncProcessEntry;
use FlowCatalyst\DTOs\Requests\SyncRoleEntry;
use FlowCatalyst\DTOs\Requests\SyncScheduledJobEntry;
use FlowCatalyst\DTOs\Requests\SyncSubscriptionEntry;

/**
 * Service for synchronizing FlowCatalyst definitions to the platform.
 *
 * This service provides a programmatic API for syncing definitions without
 * requiring the use of PHP attributes or the definition scanner. It supports
 * syncing multiple applications from a single deployment.
 *
 * Example usage:
 *
 * ```php
 * // Single application sync
 * $synchronizer = app(DefinitionSynchronizer::class);
 *
 * $result = $synchronizer->sync(
 *     SyncDefinitionSet::forApplication('my-app')
 *         ->withRoles([
 *             new RoleDefinition('admin', 'Administrator'),
 *         ])
 *         ->withEventTypes([
 *             new EventTypeDefinition('user.created', 'User Created'),
 *         ])
 * );
 *
 * // Multi-application sync
 * $results = $synchronizer->syncAll([
 *     SyncDefinitionSet::forApplication('app-one')
 *         ->withEventTypes([...]),
 *     SyncDefinitionSet::forApplication('app-two')
 *         ->withEventTypes([...]),
 * ], SyncOptions::withRemoveUnlisted());
 * ```
 */
class DefinitionSynchronizer
{
    public function __construct(
        private readonly FlowCatalystClient $client,
        // Fallback callback URL for scheduled jobs whose #[AsScheduledJob]
        // doesn't set targetUrl. The platform stores targetUrl verbatim (no
        // server-side default) and a job without one fails EVERY firing with
        // "No target URL configured" — so the sync must always send one. The
        // service provider passes APP_URL + ScheduledJobRunner's conventional
        // process path.
        private readonly ?string $defaultScheduledJobTargetUrl = null,
    ) {}

    /**
     * Sync definitions for a single application.
     *
     * @param SyncDefinitionSet $definitions The definitions to sync
     * @param SyncOptions|null $options Sync options (defaults to SyncOptions::defaults())
     * @return SyncResult The sync results
     */
    public function sync(SyncDefinitionSet $definitions, ?SyncOptions $options = null): SyncResult
    {
        $options ??= SyncOptions::defaults();
        $appCode = $definitions->applicationCode;

        $rolesResult = ['created' => 0, 'updated' => 0, 'deleted' => 0];
        $eventTypesResult = ['created' => 0, 'updated' => 0, 'deleted' => 0];
        $subscriptionsResult = ['created' => 0, 'updated' => 0, 'deleted' => 0];
        $dispatchPoolsResult = ['created' => 0, 'updated' => 0, 'deleted' => 0];
        $principalsResult = ['created' => 0, 'updated' => 0, 'deleted' => 0];
        $processesResult = ['created' => 0, 'updated' => 0, 'deleted' => 0];
        $scheduledJobsResult = ['created' => 0, 'updated' => 0, 'deleted' => 0];
        $openapiResult = ['created' => 0, 'updated' => 0, 'deleted' => 0];

        // Sync roles
        if ($options->syncRoles && $definitions->hasRoles()) {
            $rolesResult = $this->syncRoles($appCode, $definitions->getRoles(), $options->removeUnlisted);
        }

        // Sync event types
        if ($options->syncEventTypes && $definitions->hasEventTypes()) {
            $eventTypesResult = $this->syncEventTypes($appCode, $definitions->getEventTypes(), $options->removeUnlisted);
        }

        // Sync subscriptions
        if ($options->syncSubscriptions && $definitions->hasSubscriptions()) {
            $subscriptionsResult = $this->syncSubscriptions($appCode, $definitions->getSubscriptions(), $options->removeUnlisted);
        }

        // Sync dispatch pools
        if ($options->syncDispatchPools && $definitions->hasDispatchPools()) {
            $dispatchPoolsResult = $this->syncDispatchPools($appCode, $definitions->getDispatchPools(), $options->removeUnlisted);
        }

        // Sync principals (users with roles)
        if ($options->syncPrincipals && $definitions->hasPrincipals()) {
            $principalsResult = $this->syncPrincipals($appCode, $definitions->getPrincipals(), $options->removeUnlisted);
        }

        // Sync processes (workflow documentation)
        if ($options->syncProcesses && $definitions->hasProcesses()) {
            $processesResult = $this->syncProcesses($appCode, $definitions->getProcesses(), $options->removeUnlisted);
        }

        // Sync scheduled jobs
        if ($options->syncScheduledJobs && $definitions->hasScheduledJobs()) {
            $scheduledJobsResult = $this->syncScheduledJobs($appCode, $definitions->getScheduledJobs(), $options->removeUnlisted);
        }

        // Publish attached OpenAPI document
        if ($options->syncOpenapi && $definitions->hasOpenapiSpec()) {
            $openapiResult = $this->syncOpenapi($appCode, $definitions->getOpenapiSpec());
        }

        return new SyncResult(
            applicationCode: $appCode,
            roles: $rolesResult,
            eventTypes: $eventTypesResult,
            subscriptions: $subscriptionsResult,
            dispatchPools: $dispatchPoolsResult,
            principals: $principalsResult,
            processes: $processesResult,
            scheduledJobs: $scheduledJobsResult,
            openapi: $openapiResult,
        );
    }

    /**
     * Sync definitions for multiple applications.
     *
     * @param SyncDefinitionSet[] $definitionSets Array of definition sets, one per application
     * @param SyncOptions|null $options Sync options applied to all applications
     * @return SyncResult[] Array of sync results, keyed by application code
     */
    public function syncAll(array $definitionSets, ?SyncOptions $options = null): array
    {
        $results = [];

        foreach ($definitionSets as $definitions) {
            $results[$definitions->applicationCode] = $this->sync($definitions, $options);
        }

        return $results;
    }

    /**
     * Sync roles for an application.
     *
     * @param string $appCode Application code
     * @param array<array<string, mixed>> $roles Role definitions
     * @param bool $removeUnlisted Remove roles not in the local set
     * @return array{created: int, updated: int, deleted: int, error?: string}
     */
    private function syncRoles(string $appCode, array $roles, bool $removeUnlisted): array
    {
        // Validate role names before syncing
        $validationErrors = $this->validateRoles($roles);
        if (!empty($validationErrors)) {
            return [
                'created' => 0,
                'updated' => 0,
                'deleted' => 0,
                'error' => implode('; ', $validationErrors),
            ];
        }

        try {
            $entries = array_map(
                fn(array $row) => new SyncRoleEntry(
                    name: (string) ($row['name'] ?? ''),
                    displayName: isset($row['displayName']) ? (string) $row['displayName'] : null,
                    description: isset($row['description']) ? (string) $row['description'] : null,
                    permissions: $row['permissions'] ?? [],
                    clientManaged: (bool) ($row['clientManaged'] ?? false),
                ),
                $roles,
            );
            $result = $this->client->roles()->sync($appCode, $entries, $removeUnlisted);

            return [
                'created' => $result->created,
                'updated' => $result->updated,
                'deleted' => $result->deleted,
            ];
        } catch (\Exception $e) {
            return [
                'created' => 0,
                'updated' => 0,
                'deleted' => 0,
                'error' => $e->getMessage(),
            ];
        }
    }

    /**
     * Validate role definitions before syncing.
     *
     * @param array<array<string, mixed>> $roles Role definitions
     * @return string[] Validation error messages
     */
    private function validateRoles(array $roles): array
    {
        $errors = [];

        foreach ($roles as $role) {
            $name = $role['name'] ?? '';
            $error = RoleDefinition::validateName($name);
            if ($error !== null) {
                $errors[] = $error;
            }
        }

        return $errors;
    }

    /**
     * Sync event types for an application.
     *
     * @param string $appCode Application code
     * @param array<array<string, mixed>> $eventTypes Event type definitions
     * @param bool $removeUnlisted Remove event types not in the local set
     * @return array{created: int, updated: int, deleted: int, error?: string}
     */
    private function syncEventTypes(string $appCode, array $eventTypes, bool $removeUnlisted): array
    {
        try {
            $entries = array_map(
                fn(array $row) => new SyncEventTypeEntry(
                    code: $this->eventTypeCode($row, $appCode),
                    name: (string) ($row['name'] ?? ''),
                    description: isset($row['description']) ? (string) $row['description'] : null,
                ),
                $eventTypes,
            );
            $result = $this->client->eventTypes()->sync($appCode, $entries, $removeUnlisted);

            return [
                'created' => $result->created,
                'updated' => $result->updated,
                'deleted' => $result->deleted,
            ];
        } catch (\Exception $e) {
            return [
                'created' => 0,
                'updated' => 0,
                'deleted' => 0,
                'error' => $e->getMessage(),
            ];
        }
    }

    /**
     * Resolve the full event-type code from a definition row.
     *
     * Prefers an explicit `code`; otherwise assembles it from the individual
     * {application,subdomain,aggregate,event} segments. This covers both
     * EventTypeDefinition::toArray() rows (which carry their own `application`)
     * and raw scanned `#[AsEventType]` rows (which DON'T — the application is
     * the set's $appCode, applied here). Prevents a missing segment from
     * silently becoming an empty code (which the API rejects with "must follow
     * format application:subdomain:aggregate:event").
     *
     * @param array<string, mixed> $row
     */
    private function eventTypeCode(array $row, string $appCode): string
    {
        $code = (string) ($row['code'] ?? '');
        if ($code !== '') {
            return $code;
        }

        $application = $row['application'] ?? ($appCode !== '' ? $appCode : null);
        $segments = [
            $application,
            $row['subdomain'] ?? null,
            $row['aggregate'] ?? null,
            $row['event'] ?? null,
        ];
        if (!in_array(null, $segments, true) && !in_array('', $segments, true)) {
            return implode(':', array_map('strval', $segments));
        }

        return $code;
    }

    /**
     * Sync subscriptions for an application.
     *
     * @param string $appCode Application code
     * @param array<array<string, mixed>> $subscriptions Subscription definitions
     * @param bool $removeUnlisted Remove subscriptions not in the local set
     * @return array{created: int, updated: int, deleted: int, error?: string}
     */
    private function syncSubscriptions(string $appCode, array $subscriptions, bool $removeUnlisted): array
    {
        try {
            $entries = array_map(
                function (array $row) {
                    $rawBindings = $row['eventTypes'] ?? (
                        isset($row['eventTypeCode']) ? [['eventTypeCode' => $row['eventTypeCode']]] : []
                    );
                    $bindings = array_map(
                        fn(array $b) => new EventTypeBinding(
                            eventTypeCode: (string) $b['eventTypeCode'],
                            filter: isset($b['filter']) ? (string) $b['filter'] : null,
                        ),
                        $rawBindings,
                    );
                    return new SyncSubscriptionEntry(
                        code: (string) ($row['code'] ?? ''),
                        name: (string) ($row['name'] ?? ''),
                        target: (string) ($row['target'] ?? $row['endpoint'] ?? ''),
                        eventTypes: $bindings,
                        description: isset($row['description']) ? (string) $row['description'] : null,
                        connectionId: isset($row['connectionId']) ? (string) $row['connectionId'] : null,
                        dispatchPoolCode: isset($row['dispatchPoolCode']) ? (string) $row['dispatchPoolCode'] : null,
                        mode: $row['mode'] ?? null,
                        maxRetries: isset($row['maxRetries']) ? (int) $row['maxRetries'] : null,
                        timeoutSeconds: isset($row['timeoutSeconds']) ? (int) $row['timeoutSeconds'] : null,
                        dataOnly: (bool) ($row['dataOnly'] ?? false),
                    );
                },
                $subscriptions,
            );
            $result = $this->client->subscriptions()->sync($appCode, $entries, $removeUnlisted);

            return [
                'created' => $result->created,
                'updated' => $result->updated,
                'deleted' => $result->deleted,
            ];
        } catch (\Exception $e) {
            return [
                'created' => 0,
                'updated' => 0,
                'deleted' => 0,
                'error' => $e->getMessage(),
            ];
        }
    }

    /**
     * Sync dispatch pools for an application.
     *
     * @param string $appCode Application code
     * @param array<array<string, mixed>> $dispatchPools Dispatch pool definitions
     * @param bool $removeUnlisted Remove dispatch pools not in the local set
     * @return array{created: int, updated: int, deleted: int, error?: string}
     */
    private function syncDispatchPools(string $appCode, array $dispatchPools, bool $removeUnlisted): array
    {
        // Validate dispatch pool codes before syncing
        $validationErrors = $this->validateDispatchPools($dispatchPools);
        if (!empty($validationErrors)) {
            return [
                'created' => 0,
                'updated' => 0,
                'deleted' => 0,
                'error' => implode('; ', $validationErrors),
            ];
        }

        try {
            $entries = array_map(
                fn(array $row) => new SyncDispatchPoolEntry(
                    code: (string) ($row['code'] ?? ''),
                    name: (string) ($row['name'] ?? $row['code'] ?? ''),
                    description: isset($row['description']) ? (string) $row['description'] : null,
                    rateLimit: isset($row['rateLimit']) ? (int) $row['rateLimit'] : null,
                    concurrency: isset($row['concurrency']) ? (int) $row['concurrency'] : null,
                ),
                $dispatchPools,
            );
            $result = $this->client->dispatchPools()->sync($appCode, $entries, $removeUnlisted);

            return [
                'created' => $result->created,
                'updated' => $result->updated,
                'deleted' => $result->deleted,
            ];
        } catch (\Exception $e) {
            return [
                'created' => 0,
                'updated' => 0,
                'deleted' => 0,
                'error' => $e->getMessage(),
            ];
        }
    }

    /**
     * Validate dispatch pool definitions before syncing.
     *
     * @param array<array<string, mixed>> $dispatchPools Dispatch pool definitions
     * @return string[] Validation error messages
     */
    private function validateDispatchPools(array $dispatchPools): array
    {
        $errors = [];

        foreach ($dispatchPools as $pool) {
            $code = $pool['code'] ?? '';
            $error = DispatchPoolDefinition::validateCode($code);
            if ($error !== null) {
                $errors[] = $error;
            }
        }

        return $errors;
    }

    /**
     * Sync processes (workflow documentation) for an application.
     *
     * Accepts entries shaped like `ProcessDefinition::toArray()` (which
     * carry the full `code`) or scanner output from `#[AsProcess]` (which
     * carries `subdomain` + `processName` and relies on `$appCode` for
     * the first segment).
     *
     * @param string $appCode Application code
     * @param array<array<string, mixed>> $processes Process definitions
     * @param bool $removeUnlisted Archive CODE/API-sourced processes not in the local set
     * @return array{created: int, updated: int, deleted: int, error?: string}
     */
    private function syncProcesses(string $appCode, array $processes, bool $removeUnlisted): array
    {
        try {
            $entries = array_map(
                function (array $row) use ($appCode) {
                    $code = isset($row['code']) && $row['code'] !== ''
                        ? (string) $row['code']
                        : sprintf(
                            '%s:%s:%s',
                            $appCode,
                            (string) ($row['subdomain'] ?? ''),
                            (string) ($row['processName'] ?? ''),
                        );
                    /** @var string[] $tags */
                    $tags = $row['tags'] ?? [];
                    return new SyncProcessEntry(
                        code: $code,
                        name: (string) ($row['name'] ?? ''),
                        body: (string) ($row['body'] ?? ''),
                        description: isset($row['description']) ? (string) $row['description'] : null,
                        diagramType: (string) ($row['diagramType'] ?? 'mermaid'),
                        tags: array_map(fn($t) => (string) $t, $tags),
                    );
                },
                $processes,
            );
            $result = $this->client->processes()->sync($appCode, $entries, $removeUnlisted);

            return [
                'created' => $result->created,
                'updated' => $result->updated,
                'deleted' => $result->deleted,
            ];
        } catch (\Exception $e) {
            return [
                'created' => 0,
                'updated' => 0,
                'deleted' => 0,
                'error' => $e->getMessage(),
            ];
        }
    }

    /**
     * Sync principals (users with roles) for an application.
     *
     * @param string $appCode Application code
     * @param array<array<string, mixed>> $principals Principal definitions
     * @param bool $removeUnlisted Remove SDK-synced roles for unlisted principals
     * @return array{created: int, updated: int, deleted: int, error?: string}
     */
    private function syncPrincipals(string $appCode, array $principals, bool $removeUnlisted): array
    {
        try {
            $entries = array_map(
                fn(array $row) => new SyncPrincipalEntry(
                    email: (string) ($row['email'] ?? ''),
                    name: (string) ($row['name'] ?? ''),
                    roles: $row['roles'] ?? [],
                    active: isset($row['active']) ? (bool) $row['active'] : null,
                    passwordHash: isset($row['passwordHash']) ? (string) $row['passwordHash'] : null,
                ),
                $principals,
            );
            $result = $this->client->principals()->sync($appCode, $entries, $removeUnlisted);

            return [
                'created' => $result->created,
                'updated' => $result->updated,
                'deleted' => $result->deleted,
            ];
        } catch (\Exception $e) {
            return [
                'created' => 0,
                'updated' => 0,
                'deleted' => 0,
                'error' => $e->getMessage(),
            ];
        }
    }

    /**
     * Sync scheduled jobs for an application.
     *
     * The platform's scheduled-jobs sync endpoint uses `archiveUnlisted` in
     * the body rather than `removeUnlisted` as a query string; we translate.
     *
     * Jobs are grouped by their resolved `clientId` (batch key, not per-job
     * on the wire) since the sync endpoint accepts one `clientId` for the
     * whole call — one `sync()` request is issued per distinct group. In
     * the common case (all jobs share one client, or none set one at all)
     * this is a single request, same as before.
     *
     * @param string $appCode Application code
     * @param array<array<string, mixed>> $jobs Scheduled-job definitions
     * @param bool $removeUnlisted Archive jobs present on the platform but missing locally
     * @return array{created: int, updated: int, deleted: int, error?: string}
     */
    private function syncScheduledJobs(string $appCode, array $jobs, bool $removeUnlisted): array
    {
        $groups = [];
        foreach ($jobs as $row) {
            $clientId = (is_array($row) && isset($row['clientId']) && is_string($row['clientId']) && $row['clientId'] !== '')
                ? $row['clientId']
                : '';
            $groups[$clientId][] = $row;
        }

        $created = 0;
        $updated = 0;
        $deleted = 0;
        $errors = [];

        foreach ($groups as $clientId => $groupJobs) {
            try {
                $entries = array_map(
                    fn(array $row) => new SyncScheduledJobEntry(
                        code: (string) ($row['code'] ?? ''),
                        name: (string) ($row['name'] ?? ''),
                        crons: array_map(static fn($c) => (string) $c, (array) ($row['crons'] ?? [])),
                        description: isset($row['description']) ? (string) $row['description'] : null,
                        timezone: isset($row['timezone']) ? (string) $row['timezone'] : 'UTC',
                        payload: $row['payload'] ?? null,
                        concurrent: (bool) ($row['concurrent'] ?? false),
                        tracksCompletion: (bool) ($row['tracksCompletion'] ?? false),
                        timeoutSeconds: isset($row['timeoutSeconds']) ? (int) $row['timeoutSeconds'] : null,
                        deliveryMaxAttempts: isset($row['deliveryMaxAttempts']) ? (int) $row['deliveryMaxAttempts'] : 3,
                        targetUrl: isset($row['targetUrl'])
                            ? (string) $row['targetUrl']
                            : $this->defaultScheduledJobTargetUrl,
                    ),
                    $groupJobs,
                );
                $result = $this->client->scheduledJobs()->sync(
                    applicationCode: $appCode,
                    jobs: $entries,
                    clientId: $clientId !== '' ? $clientId : null,
                    archiveUnlisted: $removeUnlisted,
                );

                $created += count($result['created'] ?? []);
                $updated += count($result['updated'] ?? []);
                $deleted += count($result['archived'] ?? []);
            } catch (\Exception $e) {
                $errors[] = $e->getMessage();
            }
        }

        $out = ['created' => $created, 'updated' => $updated, 'deleted' => $deleted];
        if ($errors !== []) {
            $out['error'] = implode('; ', $errors);
        }
        return $out;
    }

    /**
     * Publish a single OpenAPI document for an application. The platform
     * short-circuits on `unchanged` and archives the prior version when it
     * differs; we normalise the response into the standard
     * `{created, updated, deleted}` shape (plus `version` for visibility).
     *
     * @param string $appCode Application code
     * @param mixed $spec Parsed OpenAPI document (associative array or stdClass)
     * @return array{created: int, updated: int, deleted: int, error?: string, version?: string}
     */
    private function syncOpenapi(string $appCode, mixed $spec): array
    {
        try {
            $response = $this->client->request('POST', "/api/applications/{$appCode}/openapi/sync", [
                'json' => ['spec' => $spec],
            ]);

            $unchanged = (bool) ($response['unchanged'] ?? false);
            $archivedPriorVersion = $response['archivedPriorVersion'] ?? null;
            $version = isset($response['version']) ? (string) $response['version'] : '';

            $created = ($unchanged || $archivedPriorVersion !== null) ? 0 : 1;
            $updated = $archivedPriorVersion !== null ? 1 : 0;

            return [
                'created' => $created,
                'updated' => $updated,
                'deleted' => 0,
                'version' => $version,
            ];
        } catch (\Exception $e) {
            return [
                'created' => 0,
                'updated' => 0,
                'deleted' => 0,
                'error' => $e->getMessage(),
            ];
        }
    }
}
