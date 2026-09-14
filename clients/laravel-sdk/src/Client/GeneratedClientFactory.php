<?php

declare(strict_types=1);

namespace FlowCatalyst\Client;

use FlowCatalyst\Client\Auth\OidcHttpPlugin;
use FlowCatalyst\Client\Auth\TokenProviderInterface;
use FlowCatalyst\Generated\Client as GeneratedClient;
use Http\Client\Common\Plugin\AddHostPlugin;
use Http\Client\Common\PluginClient;
use Http\Discovery\Psr17FactoryDiscovery;
use Http\Discovery\Psr18ClientDiscovery;

class GeneratedClientFactory
{
    public static function create(
        TokenProviderInterface $tokenProvider,
        string $baseUrl
    ): GeneratedClient {
        $uri = Psr17FactoryDiscovery::findUriFactory()->createUri($baseUrl);

        $httpClient = new PluginClient(
            Psr18ClientDiscovery::find(),
            [
                new AddHostPlugin($uri),
                new OidcHttpPlugin($tokenProvider),
            ]
        );

        return GeneratedClient::create($httpClient);
    }
}
