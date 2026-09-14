<?php

declare(strict_types=1);

namespace FlowCatalyst\Enums;

enum SubscriptionSource: string
{
    /**
     * Created/synced via SDK/API.
     */
    case API = 'API';

    /**
     * Created via user interface.
     */
    case UI = 'UI';
}
