<?php

declare(strict_types=1);

namespace FlowCatalyst\Enums;

enum ScheduledJobInstanceStatus: string
{
    case QUEUED = 'QUEUED';
    case IN_FLIGHT = 'IN_FLIGHT';
    case DELIVERED = 'DELIVERED';
    case COMPLETED = 'COMPLETED';
    case FAILED = 'FAILED';
    case DELIVERY_FAILED = 'DELIVERY_FAILED';
}
