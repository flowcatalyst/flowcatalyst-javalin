<?php

declare(strict_types=1);

namespace FlowCatalyst\ScheduledJobs;

use Illuminate\Contracts\Cache\Repository;

/**
 * Lock provider backed by Laravel's cache lock primitive (`$cache->lock()`).
 * Works with any cache driver that supports atomic locks (Redis, DynamoDB,
 * Memcached, database). Survives multiple replicas as long as they share
 * the cache backend.
 *
 *   $runner = new ScheduledJobRunner($jobs, new CacheLockProvider(Cache::store('redis')));
 */
final class CacheLockProvider implements LockProvider
{
    public function __construct(
        private readonly Repository $cache,
        private readonly string $prefix = 'fc:scheduled-job:lock:',
    ) {}

    public function acquire(string $key, int $ttlSeconds): ?callable
    {
        $store = method_exists($this->cache, 'getStore') ? $this->cache->getStore() : null;
        if ($store === null || !method_exists($store, 'lock')) {
            // Fallback for cache drivers without atomic locks — best-effort.
            $cacheKey = $this->prefix . $key;
            if ($this->cache->has($cacheKey)) return null;
            $this->cache->put($cacheKey, true, $ttlSeconds);
            return fn() => $this->cache->forget($cacheKey);
        }

        $lock = $store->lock($this->prefix . $key, $ttlSeconds);
        if (!$lock->get()) return null;

        return static function () use ($lock): void {
            try { $lock->release(); } catch (\Throwable) { /* swallow */ }
        };
    }
}
