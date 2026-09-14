<?php

declare(strict_types=1);

namespace FlowCatalyst\Client\Auth;

use Http\Client\Common\Plugin;
use Http\Promise\Promise;
use Psr\Http\Message\RequestInterface;

class OidcHttpPlugin implements Plugin
{
    public function __construct(
        private readonly TokenProviderInterface $tokenProvider
    ) {}

    public function handleRequest(
        RequestInterface $request,
        callable $next,
        callable $first
    ): Promise {
        $token = $this->tokenProvider->getAccessToken();
        $request = $request->withHeader('Authorization', "Bearer {$token}");

        return $next($request);
    }
}
