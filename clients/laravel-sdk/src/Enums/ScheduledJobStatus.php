<?php

declare(strict_types=1);

namespace FlowCatalyst\Enums;

enum ScheduledJobStatus: string
{
    case ACTIVE = 'ACTIVE';
    case PAUSED = 'PAUSED';
    case ARCHIVED = 'ARCHIVED';
}
