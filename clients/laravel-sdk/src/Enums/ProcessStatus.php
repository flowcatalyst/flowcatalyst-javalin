<?php

declare(strict_types=1);

namespace FlowCatalyst\Enums;

enum ProcessStatus: string
{
    /**
     * Active process documentation.
     */
    case CURRENT = 'CURRENT';

    /**
     * Archived process (soft-deleted).
     */
    case ARCHIVED = 'ARCHIVED';
}
