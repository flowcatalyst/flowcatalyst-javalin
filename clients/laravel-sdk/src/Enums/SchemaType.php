<?php

declare(strict_types=1);

namespace FlowCatalyst\Enums;

enum SchemaType: string
{
    /**
     * JSON Schema (draft-07+).
     */
    case JSON_SCHEMA = 'JSON_SCHEMA';

    /**
     * Protocol Buffers.
     */
    case PROTO = 'PROTO';

    /**
     * XML Schema Definition.
     */
    case XSD = 'XSD';
}
