<?php

declare(strict_types=1);

namespace FlowCatalyst\Client\Auth;

use Closure;
use FlowCatalyst\Exceptions\AuthenticationException;

/**
 * Token provider for user access tokens.
 *
 * Use this when you already have a user access token (e.g., from OIDC login)
 * and want to make API calls on behalf of that user.
 *
 * @example Static token
 * ```php
 * $provider = new UserTokenProvider('eyJ...');
 * $client = new FlowCatalystClient($provider, 'https://api.example.com');
 * ```
 *
 * @example Dynamic token from session
 * ```php
 * $provider = new UserTokenProvider(fn() => session('access_token'));
 * $client = new FlowCatalystClient($provider, 'https://api.example.com');
 * ```
 */
class UserTokenProvider implements TokenProviderInterface
{
    /**
     * @param string|Closure(): string $token The access token or a callable that returns it
     */
    public function __construct(
        private readonly string|Closure $token
    ) {
    }

    /**
     * Get the current access token.
     *
     * @throws AuthenticationException
     */
    public function getAccessToken(): string
    {
        $token = $this->resolveToken();

        if (empty($token)) {
            throw AuthenticationException::tokenExpired('No access token available');
        }

        return $token;
    }

    /**
     * For user tokens, refresh simply returns the current token.
     *
     * If you need token refresh capability, implement a custom TokenProviderInterface
     * that handles the refresh flow with your authentication system.
     *
     * @throws AuthenticationException
     */
    public function refreshToken(): string
    {
        return $this->getAccessToken();
    }

    /**
     * Resolve the token value.
     */
    private function resolveToken(): string
    {
        if ($this->token instanceof Closure) {
            return ($this->token)();
        }

        return $this->token;
    }
}
