<?php

declare(strict_types=1);

namespace FlowCatalyst\Enums;

enum MessageType: string
{
    /**
     * CloudEvents-compatible event for subscription matching.
     */
    case EVENT = 'EVENT';

    /**
     * Direct webhook dispatch job without subscription matching.
     */
    case DISPATCH_JOB = 'DISPATCH_JOB';

    /**
     * Audit log entry for compliance and debugging.
     */
    case AUDIT_LOG = 'AUDIT_LOG';
}
