<?php

declare(strict_types=1);

namespace FlowCatalyst\Enums;

enum SubscriptionStatus: string
{
    /**
     * Subscription is active and will create dispatch jobs for matching events.
     */
    case ACTIVE = 'ACTIVE';

    /**
     * Subscription is paused and will not create dispatch jobs.
     */
    case PAUSED = 'PAUSED';
}
