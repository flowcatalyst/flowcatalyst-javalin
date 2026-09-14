<?php

declare(strict_types=1);

namespace FlowCatalyst\ScheduledJobs;

/**
 * Default lock provider — every acquire succeeds. Use when you only run a
 * single consumer pod, or when the job is `concurrent: true` anyway.
 */
final class NoOpLockProvider implements LockProvider
{
    public function acquire(string $key, int $ttlSeconds): ?callable
    {
        return static function (): void {
            // no-op release
        };
    }
}
