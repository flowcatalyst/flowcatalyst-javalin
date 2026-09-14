<?php

declare(strict_types=1);

namespace FlowCatalyst\Auth\Http\Middleware;

use Closure;
use FlowCatalyst\Auth\DTOs\FlowCatalystUser;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

/**
 * Guard for web routes — redirects to `/flowcatalyst/login?returnTo=…` if
 * the request is unauthenticated. Apply AFTER {@see AuthenticateFc}.
 *
 *   Route::get('/dashboard', $handler)->middleware(['fc.auth', 'fc.session']);
 */
final class RequireSession
{
    public function handle(Request $request, Closure $next): Response
    {
        $principal = $request->attributes->get('fc.principal');
        if ($principal instanceof FlowCatalystUser) {
            return $next($request);
        }
        $returnTo = urlencode($request->fullUrl());
        return redirect("/flowcatalyst/login?returnTo={$returnTo}");
    }
}
