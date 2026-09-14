<?php

declare(strict_types=1);

namespace FlowCatalyst\Enums;

enum SpecVersionStatus: string
{
    /**
     * Being finalized, not ready for production use.
     */
    case FINALISING = 'FINALISING';

    /**
     * Active version (one per major version).
     */
    case CURRENT = 'CURRENT';

    /**
     * Superseded but still valid for reading.
     */
    case DEPRECATED = 'DEPRECATED';
}
