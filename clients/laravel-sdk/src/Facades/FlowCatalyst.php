<?php

declare(strict_types=1);

namespace FlowCatalyst\Facades;

use FlowCatalyst\Client\FlowCatalystClient;
use FlowCatalyst\Client\Resources\Applications;
use FlowCatalyst\Client\Resources\DispatchPools;
use FlowCatalyst\Client\Resources\EventTypes;
use FlowCatalyst\Client\Resources\Permissions;
use FlowCatalyst\Client\Resources\Roles;
use FlowCatalyst\Client\Resources\Subscriptions;
use Illuminate\Support\Facades\Facade;

/**
 * @method static EventTypes eventTypes()
 * @method static Subscriptions subscriptions()
 * @method static DispatchPools dispatchPools()
 * @method static Roles roles()
 * @method static Permissions permissions()
 * @method static Applications applications()
 * @method static array request(string $method, string $endpoint, array $options = [])
 *
 * @see FlowCatalystClient
 */
class FlowCatalyst extends Facade
{
    /**
     * Get the registered name of the component.
     */
    protected static function getFacadeAccessor(): string
    {
        return FlowCatalystClient::class;
    }
}
