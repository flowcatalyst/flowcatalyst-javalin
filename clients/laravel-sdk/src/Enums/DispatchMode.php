<?php

declare(strict_types=1);

namespace FlowCatalyst\Enums;

enum DispatchMode: string
{
    /**
     * No message group ordering, process as fast as possible.
     */
    case IMMEDIATE = 'IMMEDIATE';

    /**
     * Message group ordering with error skip (continue on failure).
     */
    case NEXT_ON_ERROR = 'NEXT_ON_ERROR';

    /**
     * Message group ordering with error blocking (stop on failure).
     */
    case BLOCK_ON_ERROR = 'BLOCK_ON_ERROR';
}
