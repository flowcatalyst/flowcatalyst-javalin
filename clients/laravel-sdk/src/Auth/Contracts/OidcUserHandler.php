<?php

declare(strict_types=1);

namespace FlowCatalyst\Auth\Contracts;

use FlowCatalyst\Auth\DTOs\FlowCatalystUser;

/**
 * Contract for handling OIDC-authenticated users.
 *
 * Implement this interface to customize how FlowCatalyst users are mapped to your
 * Laravel application's user model. This allows you to:
 *
 * - Create or update local User records
 * - Assign your own roles and permissions
 * - Store additional user metadata
 * - Integrate with your existing authentication system
 *
 * @example Basic implementation:
 * ```php
 * class MyOidcUserHandler implements OidcUserHandler
 * {
 *     public function handleAuthenticatedUser(FlowCatalystUser $user): mixed
 *     {
 *         $localUser = User::updateOrCreate(
 *             ['email' => $user->email],
 *             [
 *                 'name' => $user->name,
 *                 'flowcatalyst_sub' => $user->sub,
 *             ]
 *         );
 *
 *         Auth::login($localUser, remember: true);
 *         return $localUser;
 *     }
 *
 *     public function handleLogout(): void
 *     {
 *         Auth::logout();
 *         session()->invalidate();
 *         session()->regenerateToken();
 *     }
 *
 *     public function getPostLoginRedirect(): string
 *     {
 *         return route('dashboard');
 *     }
 *
 *     public function getPostLogoutRedirect(): string
 *     {
 *         return route('home');
 *     }
 * }
 * ```
 *
 * Then register in a service provider:
 * ```php
 * $this->app->bind(OidcUserHandler::class, MyOidcUserHandler::class);
 * ```
 */
interface OidcUserHandler
{
    /**
     * Handle an authenticated FlowCatalyst user.
     *
     * This method is called after successful OIDC authentication. Use it to:
     * - Create or update your local User model
     * - Assign roles/permissions based on FlowCatalyst claims
     * - Log the user into your Laravel application
     *
     * @param FlowCatalystUser $user The authenticated FlowCatalyst user with claims
     * @return mixed Your local user model or any value you want to pass through
     */
    public function handleAuthenticatedUser(FlowCatalystUser $user): mixed;

    /**
     * Handle user logout.
     *
     * Called when the user initiates logout. Implement to:
     * - Log out the user from Laravel's Auth system
     * - Clear any session data
     * - Revoke any stored tokens
     */
    public function handleLogout(): void;

    /**
     * Get the URL to redirect to after successful login.
     *
     * @return string The redirect URL (can be a route name or full URL)
     */
    public function getPostLoginRedirect(): string;

    /**
     * Get the URL to redirect to after logout.
     *
     * @return string The redirect URL (can be a route name or full URL)
     */
    public function getPostLogoutRedirect(): string;
}
