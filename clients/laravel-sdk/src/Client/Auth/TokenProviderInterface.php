<?php

declare(strict_types=1);

namespace FlowCatalyst\Client\Auth;

use FlowCatalyst\Exceptions\AuthenticationException;

/**
 * Interface for token providers.
 *
 * Implement this interface to provide custom token acquisition logic.
 */
interface TokenProviderInterface
{
    /**
     * Get the current access token.
     *
     * @throws AuthenticationException
     */
    public function getAccessToken(): string;

    /**
     * Refresh/acquire a new access token.
     *
     * For user tokens, this may simply return the current token if refresh is not supported.
     *
     * @throws AuthenticationException
     */
    public function refreshToken(): string;
}
