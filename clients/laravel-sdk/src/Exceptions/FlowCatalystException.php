<?php

declare(strict_types=1);

namespace FlowCatalyst\Exceptions;

use Exception;

class FlowCatalystException extends Exception
{
    protected ?array $context = null;

    public function __construct(
        string $message = '',
        int $code = 0,
        ?\Throwable $previous = null,
        ?array $context = null
    ) {
        parent::__construct($message, $code, $previous);
        $this->context = $context;
    }

    /**
     * Get additional context for the exception.
     */
    public function getContext(): ?array
    {
        return $this->context;
    }

    /**
     * Create an exception with context.
     */
    public static function withContext(string $message, array $context, int $code = 0): static
    {
        return new static($message, $code, null, $context);
    }
}
