<?php

declare(strict_types=1);

namespace FlowCatalyst\ScheduledJobs;

/**
 * Distributed lock interface for the ScheduledJobRunner.
 *
 * The platform DOES NOT enforce concurrency for `concurrent: false` jobs —
 * it always fires the cron tick and POSTs to the SDK. Single-instance-at-a-
 * time is the SDK consumer's responsibility, implemented via this hook.
 *
 * Default `NoOpLockProvider` always succeeds. For real production use, plug
 * in a Redis/Postgres-advisory backed implementation. Laravel apps can
 * trivially wrap `\Illuminate\Cache\Lock` here.
 */
interface LockProvider
{
    /**
     * Try to acquire `$key` for at most `$ttlSeconds`. Return a release
     * callable on success, or `null` if the lock is held elsewhere — never
     * block.
     *
     * @return (callable(): void)|null
     */
    public function acquire(string $key, int $ttlSeconds): ?callable;
}
