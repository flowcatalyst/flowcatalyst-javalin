<?php

declare(strict_types=1);

namespace FlowCatalyst\Exceptions;

class ValidationException extends FlowCatalystException
{
    protected array $errors = [];

    public function __construct(
        string $message = 'Validation failed',
        array $errors = [],
        int $code = 422
    ) {
        parent::__construct($message, $code, null, ['errors' => $errors]);
        $this->errors = $errors;
    }

    /**
     * Get the validation errors.
     */
    public function getErrors(): array
    {
        return $this->errors;
    }

    /**
     * Create from API error response.
     */
    public static function fromResponse(array $response): static
    {
        $message = $response['error'] ?? $response['message'] ?? 'Validation failed';
        $errors = $response['errors'] ?? [];

        return new static($message, $errors);
    }
}
