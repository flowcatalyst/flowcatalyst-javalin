<?php

declare(strict_types=1);

namespace FlowCatalyst\Sync;

/**
 * Represents a dispatch pool definition for syncing to FlowCatalyst.
 *
 * Can be used directly or converted to array for the sync API.
 *
 * Example:
 * ```php
 * $pool = DispatchPoolDefinition::make('default')
 *     ->withName('Default Pool')
 *     ->withRateLimit(100)
 *     ->withConcurrency(10);
 * ```
 */
final class DispatchPoolDefinition
{
    /**
     * Pool code format: lowercase alphanumeric with hyphens/underscores, starting with letter.
     */
    private const CODE_PATTERN = '/^[a-z][a-z0-9_-]*$/';

    /**
     * @param string $code Unique pool code (e.g., 'default', 'high-priority')
     * @param string|null $name Human-readable name
     * @param string|null $description Pool description
     * @param int $rateLimit Maximum dispatches per minute (default 100)
     * @param int $concurrency Maximum concurrent dispatches (default 10)
     */
    public function __construct(
        public readonly string $code,
        public readonly ?string $name = null,
        public readonly ?string $description = null,
        public readonly int $rateLimit = 100,
        public readonly int $concurrency = 10,
    ) {}

    /**
     * Create a new dispatch pool definition with fluent syntax.
     */
    public static function make(string $code): self
    {
        return new self($code);
    }

    /**
     * Validate the pool code.
     *
     * @return string|null Error message if invalid, null if valid
     */
    public function validate(): ?string
    {
        return self::validateCode($this->code);
    }

    /**
     * Check if this pool definition is valid.
     */
    public function isValid(): bool
    {
        return $this->validate() === null;
    }

    /**
     * Validate a pool code.
     *
     * Rules:
     * - Lowercase alphanumeric with hyphens and underscores only
     * - Must start with a letter
     * - At least 1 character
     *
     * @return string|null Error message if invalid, null if valid
     */
    public static function validateCode(string $code): ?string
    {
        if (empty($code)) {
            return 'Pool code cannot be empty';
        }

        if (!preg_match(self::CODE_PATTERN, $code)) {
            return "Pool code must be lowercase alphanumeric with hyphens/underscores, starting with a letter: {$code}";
        }

        return null;
    }

    /**
     * Create a copy with a different name.
     */
    public function withName(string $name): self
    {
        return new self(
            code: $this->code,
            name: $name,
            description: $this->description,
            rateLimit: $this->rateLimit,
            concurrency: $this->concurrency,
        );
    }

    /**
     * Create a copy with a different description.
     */
    public function withDescription(string $description): self
    {
        return new self(
            code: $this->code,
            name: $this->name,
            description: $description,
            rateLimit: $this->rateLimit,
            concurrency: $this->concurrency,
        );
    }

    /**
     * Create a copy with a different rate limit.
     */
    public function withRateLimit(int $rateLimit): self
    {
        return new self(
            code: $this->code,
            name: $this->name,
            description: $this->description,
            rateLimit: $rateLimit,
            concurrency: $this->concurrency,
        );
    }

    /**
     * Create a copy with a different concurrency.
     */
    public function withConcurrency(int $concurrency): self
    {
        return new self(
            code: $this->code,
            name: $this->name,
            description: $this->description,
            rateLimit: $this->rateLimit,
            concurrency: $concurrency,
        );
    }

    /**
     * Convert to array for the sync API.
     *
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        $data = ['code' => $this->code];

        if ($this->name !== null) {
            $data['name'] = $this->name;
        }

        if ($this->description !== null) {
            $data['description'] = $this->description;
        }

        $data['rateLimit'] = $this->rateLimit;
        $data['concurrency'] = $this->concurrency;

        return $data;
    }

    /**
     * Create from array (e.g., from cached definitions).
     *
     * @param array<string, mixed> $data
     */
    public static function fromArray(array $data): self
    {
        return new self(
            code: $data['code'],
            name: $data['name'] ?? null,
            description: $data['description'] ?? null,
            rateLimit: $data['rateLimit'] ?? 100,
            concurrency: $data['concurrency'] ?? 10,
        );
    }
}
