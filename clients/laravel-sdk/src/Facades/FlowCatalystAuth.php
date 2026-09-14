<?php

declare(strict_types=1);

namespace FlowCatalyst\Facades;

use FlowCatalyst\Auth\DefaultOidcUserHandler;
use FlowCatalyst\Auth\DTOs\FlowCatalystUser;
use Illuminate\Support\Facades\Facade;

/**
 * Facade for FlowCatalyst OIDC user authentication helpers.
 *
 * @method static FlowCatalystUser|null user() Get the current FlowCatalyst user
 * @method static bool check() Check if a FlowCatalyst user is authenticated
 * @method static bool guest() Check if no FlowCatalyst user is authenticated
 *
 * @see \FlowCatalyst\Auth\DefaultOidcUserHandler
 */
class FlowCatalystAuth extends Facade
{
    /**
     * Get the current FlowCatalyst user.
     *
     * Prefers a request-attached principal set by the `fc.auth` middleware
     * (which handles both Bearer and session). Falls back to the legacy
     * session-only lookup so apps that haven't wired the middleware yet
     * keep working.
     */
    public static function user(): ?FlowCatalystUser
    {
        if (function_exists('app')) {
            try {
                $request = app('request');
                if ($request instanceof \Illuminate\Http\Request) {
                    $attached = $request->attributes->get('fc.principal');
                    if ($attached instanceof FlowCatalystUser) {
                        return $attached;
                    }
                }
            } catch (\Throwable) {
                // app container not booted — fall through.
            }
        }
        return DefaultOidcUserHandler::getCurrentUser();
    }

    /**
     * Check if a FlowCatalyst user is authenticated (session OR Bearer).
     */
    public static function check(): bool
    {
        return self::user() !== null;
    }

    /**
     * Check if no FlowCatalyst user is authenticated.
     */
    public static function guest(): bool
    {
        return !self::check();
    }

    /**
     * Get the login URL.
     */
    public static function loginUrl(?string $returnUrl = null): string
    {
        $url = route('flowcatalyst.login');
        if ($returnUrl) {
            $url .= '?' . http_build_query(['return_url' => $returnUrl]);
        }
        return $url;
    }

    /**
     * Get the logout URL.
     */
    public static function logoutUrl(): string
    {
        return route('flowcatalyst.logout');
    }

    protected static function getFacadeAccessor(): string
    {
        // This facade uses static methods, so we don't need a real accessor
        return 'flowcatalyst.auth';
    }
}
