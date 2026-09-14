<?php

declare(strict_types=1);

namespace FlowCatalyst\Enums;

enum ProcessSource: string
{
    /**
     * Declared in application code (discovered via `#[AsProcess]` attribute
     * or registered through the sync command).
     */
    case CODE = 'CODE';

    /**
     * Created via the platform API (typically programmatic, non-code).
     */
    case API = 'API';

    /**
     * Created via the platform admin UI.
     */
    case UI = 'UI';
}
