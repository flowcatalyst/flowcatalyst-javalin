<?php

declare(strict_types=1);

namespace FlowCatalyst\Auth\Http\Middleware;

use Closure;
use FlowCatalyst\Auth\DTOs\FlowCatalystUser;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Symfony\Component\HttpFoundation\Response as SymfonyResponse;

/**
 * Guard for API routes — returns 401 JSON if the request is
 * unauthenticated. Apply AFTER {@see AuthenticateFc}.
 *
 *   Route::post('/api/orders', $handler)->middleware(['fc.auth', 'fc.bearer']);
 */
final class RequireBearer
{
    public function handle(Request $request, Closure $next): SymfonyResponse
    {
        $principal = $request->attributes->get('fc.principal');
        if ($principal instanceof FlowCatalystUser) {
            return $next($request);
        }
        return response()->json(['error' => 'unauthorized'], Response::HTTP_UNAUTHORIZED, [
            'WWW-Authenticate' => 'Bearer realm="flowcatalyst"',
        ]);
    }
}
