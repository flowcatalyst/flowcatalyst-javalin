<?php

declare(strict_types=1);

namespace FlowCatalyst\Enums;

enum ConnectionStatus: string
{
    /**
     * Connection is active and can be used for dispatching.
     */
    case ACTIVE = 'ACTIVE';

    /**
     * Connection is paused and won't dispatch.
     */
    case PAUSED = 'PAUSED';
}
