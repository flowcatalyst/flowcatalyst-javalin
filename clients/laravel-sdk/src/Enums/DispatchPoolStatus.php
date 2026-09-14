<?php

declare(strict_types=1);

namespace FlowCatalyst\Enums;

enum DispatchPoolStatus: string
{
    /**
     * Pool is active and can process dispatch jobs.
     */
    case ACTIVE = 'ACTIVE';

    /**
     * Pool is temporarily suspended and won't process jobs.
     */
    case SUSPENDED = 'SUSPENDED';

    /**
     * Pool is soft-deleted and cannot be used.
     */
    case ARCHIVED = 'ARCHIVED';
}
