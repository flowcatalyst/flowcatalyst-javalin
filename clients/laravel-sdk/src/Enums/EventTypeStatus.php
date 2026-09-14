<?php

declare(strict_types=1);

namespace FlowCatalyst\Enums;

enum EventTypeStatus: string
{
    /**
     * Active event type.
     */
    case CURRENT = 'CURRENT';

    /**
     * Archived event type (soft-deleted).
     */
    case ARCHIVE = 'ARCHIVE';
}
