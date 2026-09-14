<?php

declare(strict_types=1);

namespace FlowCatalyst\Auth\Support;

use GuzzleHttp\Client as HttpClient;
use Illuminate\Contracts\Cache\Repository as Cache;

/**
 * Caches OIDC discovery + JWKS keys per issuer with a TTL.
 *
 * Lazy first-use fetch of `{baseUrl}/.well-known/openid-configuration`,
 * then `{jwks_uri}`. Stored under the Laravel cache so concurrent workers
 * share them.
 *
 * No JWT library dependency — we keep the raw JWK objects and let
 * {@see AccessTokenValidator} verify signatures via OpenSSL directly.
 */
final class JwksCache
{
    public function __construct(
        private readonly HttpClient $http,
        private readonly Cache $cache,
        private readonly int $ttlSeconds = 300,
    ) {}

    /**
     * Return JWKS entries keyed by `kid` for the platform at `$baseUrl`.
     * Each entry is the raw JWK as an associative array (n, e, kid, alg…).
     *
     * @return array<string, array<string, mixed>>
     */
    public function keys(string $baseUrl): array
    {
        $cacheKey = 'fc.jwks.' . sha1($baseUrl);
        $cached = $this->cache->get($cacheKey);
        if (is_array($cached) && isset($cached['keys'])) {
            /** @var array<string, array<string, mixed>> */
            return $cached['keys'];
        }

        $base = rtrim($baseUrl, '/');
        $discoveryRaw = $this->http
            ->get($base . '/.well-known/openid-configuration', [
                'headers' => ['Accept' => 'application/json'],
            ])
            ->getBody()
            ->getContents();
        /** @var array<string, mixed> $doc */
        $doc = json_decode($discoveryRaw, true, 512, JSON_THROW_ON_ERROR);
        $jwksUri = $doc['jwks_uri'] ?? throw new \RuntimeException('discovery doc missing jwks_uri');
        if (!is_string($jwksUri)) {
            throw new \RuntimeException('discovery doc jwks_uri is not a string');
        }

        $jwksRaw = $this->http
            ->get($jwksUri, ['headers' => ['Accept' => 'application/json']])
            ->getBody()
            ->getContents();
        /** @var array<string, mixed> $jwks */
        $jwks = json_decode($jwksRaw, true, 512, JSON_THROW_ON_ERROR);
        if (!isset($jwks['keys']) || !is_array($jwks['keys'])) {
            throw new \RuntimeException('JWKS document missing keys array');
        }

        $byKid = [];
        foreach ($jwks['keys'] as $key) {
            if (!is_array($key) || !isset($key['kid']) || !is_string($key['kid'])) {
                continue;
            }
            $byKid[$key['kid']] = $key;
        }

        $this->cache->put($cacheKey, ['keys' => $byKid, 'issuer' => $doc['issuer'] ?? null], $this->ttlSeconds);
        return $byKid;
    }

    public function invalidate(string $baseUrl): void
    {
        $this->cache->forget('fc.jwks.' . sha1($baseUrl));
    }
}
