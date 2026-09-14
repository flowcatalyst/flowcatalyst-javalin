<?php

declare(strict_types=1);

namespace FlowCatalyst\Auth\Http\Middleware;

use Closure;
use FlowCatalyst\Auth\DTOs\FlowCatalystUser;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Symfony\Component\HttpFoundation\Response as SymfonyResponse;

/**
 * Guard that accepts either mechanism. On miss:
 *   - 302 to login if the request is a browser navigation
 *     (`Accept: text/html`, GET/HEAD).
 *   - 401 JSON otherwise.
 *
 *   Route::get('/api/me', $handler)->middleware(['fc.auth', 'fc.any']);
 */
final class RequireAuth
{
    public function handle(Request $request, Closure $next): SymfonyResponse
    {
        $principal = $request->attributes->get('fc.principal');
        if ($principal instanceof FlowCatalystUser) {
            return $next($request);
        }
        if ($this->isHtmlNavigation($request)) {
            $returnTo = urlencode($request->fullUrl());
            return redirect("/flowcatalyst/login?returnTo={$returnTo}");
        }
        return response()->json(['error' => 'unauthorized'], Response::HTTP_UNAUTHORIZED, [
            'WWW-Authenticate' => 'Bearer realm="flowcatalyst"',
        ]);
    }

    private function isHtmlNavigation(Request $request): bool
    {
        $method = strtoupper($request->getMethod());
        if ($method !== 'GET' && $method !== 'HEAD') {
            return false;
        }
        $accept = $request->headers->get('Accept', '');
        return is_string($accept) && str_contains($accept, 'text/html');
    }
}
