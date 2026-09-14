<?php

declare(strict_types=1);

namespace FlowCatalyst\Exceptions;

class AuthenticationException extends FlowCatalystException
{
    /**
     * Create an exception for invalid credentials.
     */
    public static function invalidCredentials(): static
    {
        return new static('Invalid client credentials. Please check your FLOWCATALYST_CLIENT_ID and FLOWCATALYST_CLIENT_SECRET.');
    }

    /**
     * Create an exception for token fetch failure.
     */
    public static function tokenFetchFailed(string $reason): static
    {
        return new static("Failed to obtain access token: {$reason}");
    }

    /**
     * Create an exception for expired token.
     */
    public static function tokenExpired(?string $message = null): static
    {
        return new static($message ?? 'Access token has expired and could not be refreshed.');
    }

    /**
     * Create an exception for missing configuration.
     */
    public static function missingConfiguration(string $key): static
    {
        return new static("Missing required configuration: {$key}. Please set {$key} in your environment.");
    }
}
