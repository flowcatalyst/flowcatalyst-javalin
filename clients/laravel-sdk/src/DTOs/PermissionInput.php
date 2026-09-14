<?php

declare(strict_types=1);

namespace FlowCatalyst\DTOs;

/**
 * Structured permission input for role definitions.
 *
 * Format: {application}:{context}:{aggregate}:{action}
 *
 * Usage:
 * ```php
 * $permission = new PermissionInput(
 *     application: 'myapp',
 *     context: 'orders',
 *     aggregate: 'order',
 *     action: 'view'
 * );
 * // Produces: "myapp:orders:order:view"
 *
 * // Or use the static factory:
 * $permission = PermissionInput::make('myapp', 'orders', 'order', 'view');
 * ```
 */
final class PermissionInput
{
    /**
     * Segment format: lowercase alphanumeric with hyphens/underscores, starting with letter.
     */
    private const SEGMENT_PATTERN = '/^[a-z][a-z0-9_-]*$/';

    /**
     * @param string $application Application code (e.g., "myapp")
     * @param string $context Bounded context (e.g., "orders", "iam")
     * @param string $aggregate Resource/entity (e.g., "order", "user")
     * @param string $action Operation (e.g., "view", "create", "update", "delete")
     */
    public function __construct(
        public readonly string $application,
        public readonly string $context,
        public readonly string $aggregate,
        public readonly string $action,
    ) {}

    /**
     * Create a new permission input with factory syntax.
     */
    public static function make(
        string $application,
        string $context,
        string $aggregate,
        string $action
    ): self {
        return new self($application, $context, $aggregate, $action);
    }

    /**
     * Validate all segments of this permission.
     *
     * @return string|null Error message if invalid, null if valid
     */
    public function validate(): ?string
    {
        $appError = self::validateSegment('application', $this->application);
        if ($appError !== null) {
            return $appError;
        }

        $ctxError = self::validateSegment('context', $this->context);
        if ($ctxError !== null) {
            return $ctxError;
        }

        $aggError = self::validateSegment('aggregate', $this->aggregate);
        if ($aggError !== null) {
            return $aggError;
        }

        $actError = self::validateSegment('action', $this->action);
        if ($actError !== null) {
            return $actError;
        }

        return null;
    }

    /**
     * Check if this permission input is valid.
     */
    public function isValid(): bool
    {
        return $this->validate() === null;
    }

    /**
     * Validate a segment value.
     *
     * @return string|null Error message if invalid, null if valid
     */
    public static function validateSegment(string $fieldName, string $value): ?string
    {
        if (empty($value)) {
            return "{$fieldName} is required";
        }

        if (!preg_match(self::SEGMENT_PATTERN, $value)) {
            return "{$fieldName} must be lowercase alphanumeric with hyphens/underscores, starting with a letter";
        }

        return null;
    }

    /**
     * Build the full permission string.
     *
     * @return string Permission in format {application}:{context}:{aggregate}:{action}
     */
    public function toPermissionString(): string
    {
        return strtolower("{$this->application}:{$this->context}:{$this->aggregate}:{$this->action}");
    }

    /**
     * Convert to array for API sync.
     *
     * @return array<string, string>
     */
    public function toArray(): array
    {
        return [
            'application' => strtolower($this->application),
            'context' => strtolower($this->context),
            'aggregate' => strtolower($this->aggregate),
            'action' => strtolower($this->action),
        ];
    }

    /**
     * Create from array.
     *
     * @param array<string, string> $data
     */
    public static function fromArray(array $data): self
    {
        return new self(
            application: $data['application'],
            context: $data['context'],
            aggregate: $data['aggregate'],
            action: $data['action'],
        );
    }

    /**
     * Parse a permission string into a PermissionInput.
     *
     * @param string $permissionString Permission string in format {app}:{context}:{aggregate}:{action}
     * @throws \InvalidArgumentException If the string doesn't have exactly 4 segments
     */
    public static function fromString(string $permissionString): self
    {
        $parts = explode(':', $permissionString);

        if (count($parts) !== 4) {
            throw new \InvalidArgumentException(
                "Permission string must have exactly 4 segments (application:context:aggregate:action). Got: {$permissionString}"
            );
        }

        return new self(
            application: $parts[0],
            context: $parts[1],
            aggregate: $parts[2],
            action: $parts[3],
        );
    }
}
