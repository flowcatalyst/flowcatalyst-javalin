<?php

declare(strict_types=1);

namespace FlowCatalyst\Sync;

/**
 * Represents a subscription definition for syncing to FlowCatalyst.
 *
 * Can be used directly or converted to array for the sync API.
 */
final class SubscriptionDefinition
{
    /**
     * @param string $code Unique subscription code
     * @param string $name Human-readable name
     * @param string $connectionId Connection ID for dispatch routing
     * @param string $queue Queue name for delivery
     * @param string $dispatchPoolCode Code of the dispatch pool to use
     * @param string|null $applicationCode Application code this subscription belongs to
     * @param string|null $description Subscription description
     * @param bool $clientScoped Whether this subscription is client-scoped
     * @param string|null $eventTypeCode Code of the event type to subscribe to (resolved to ID by platform)
     * @param int $maxRetries Maximum retry attempts (default: 3)
     * @param int $retryDelaySeconds Delay between retries in seconds (default: 60)
     * @param int $timeoutSeconds Webhook timeout in seconds (default: 30)
     * @param bool $active Whether the subscription is active (default: true)
     */
    public function __construct(
        public readonly string $code,
        public readonly string $name,
        public readonly string $connectionId,
        public readonly string $queue,
        public readonly string $dispatchPoolCode,
        public readonly ?string $applicationCode = null,
        public readonly ?string $description = null,
        public readonly bool $clientScoped = false,
        public readonly ?string $eventTypeCode = null,
        public readonly int $maxRetries = 3,
        public readonly int $retryDelaySeconds = 60,
        public readonly int $timeoutSeconds = 30,
        public readonly bool $active = true,
    ) {}

    /**
     * Create a new subscription definition with fluent syntax.
     */
    public static function make(
        string $code,
        string $name,
        string $connectionId,
        string $queue,
        string $dispatchPoolCode
    ): self {
        return new self($code, $name, $connectionId, $queue, $dispatchPoolCode);
    }

    /**
     * Create a copy with a different description.
     */
    public function withDescription(string $description): self
    {
        return new self(
            code: $this->code,
            name: $this->name,
            connectionId: $this->connectionId,
            queue: $this->queue,
            dispatchPoolCode: $this->dispatchPoolCode,
            applicationCode: $this->applicationCode,
            description: $description,
            clientScoped: $this->clientScoped,
            eventTypeCode: $this->eventTypeCode,
            maxRetries: $this->maxRetries,
            retryDelaySeconds: $this->retryDelaySeconds,
            timeoutSeconds: $this->timeoutSeconds,
            active: $this->active,
        );
    }

    /**
     * Create a copy that is client-scoped.
     */
    public function clientScoped(bool $clientScoped = true): self
    {
        return new self(
            code: $this->code,
            name: $this->name,
            connectionId: $this->connectionId,
            queue: $this->queue,
            dispatchPoolCode: $this->dispatchPoolCode,
            applicationCode: $this->applicationCode,
            description: $this->description,
            clientScoped: $clientScoped,
            eventTypeCode: $this->eventTypeCode,
            maxRetries: $this->maxRetries,
            retryDelaySeconds: $this->retryDelaySeconds,
            timeoutSeconds: $this->timeoutSeconds,
            active: $this->active,
        );
    }

    /**
     * Create a copy that subscribes to a specific event type.
     */
    public function forEventType(string $eventTypeCode): self
    {
        return new self(
            code: $this->code,
            name: $this->name,
            connectionId: $this->connectionId,
            queue: $this->queue,
            dispatchPoolCode: $this->dispatchPoolCode,
            applicationCode: $this->applicationCode,
            description: $this->description,
            clientScoped: $this->clientScoped,
            eventTypeCode: $eventTypeCode,
            maxRetries: $this->maxRetries,
            retryDelaySeconds: $this->retryDelaySeconds,
            timeoutSeconds: $this->timeoutSeconds,
            active: $this->active,
        );
    }

    /**
     * Create a copy with different retry settings.
     */
    public function withRetry(int $maxRetries, int $delaySeconds = 60): self
    {
        return new self(
            code: $this->code,
            name: $this->name,
            connectionId: $this->connectionId,
            queue: $this->queue,
            dispatchPoolCode: $this->dispatchPoolCode,
            applicationCode: $this->applicationCode,
            description: $this->description,
            clientScoped: $this->clientScoped,
            eventTypeCode: $this->eventTypeCode,
            maxRetries: $maxRetries,
            retryDelaySeconds: $delaySeconds,
            timeoutSeconds: $this->timeoutSeconds,
            active: $this->active,
        );
    }

    /**
     * Create a copy with a different timeout.
     */
    public function withTimeout(int $seconds): self
    {
        return new self(
            code: $this->code,
            name: $this->name,
            connectionId: $this->connectionId,
            queue: $this->queue,
            dispatchPoolCode: $this->dispatchPoolCode,
            applicationCode: $this->applicationCode,
            description: $this->description,
            clientScoped: $this->clientScoped,
            eventTypeCode: $this->eventTypeCode,
            maxRetries: $this->maxRetries,
            retryDelaySeconds: $this->retryDelaySeconds,
            timeoutSeconds: $seconds,
            active: $this->active,
        );
    }

    /**
     * Create a copy that is inactive.
     */
    public function inactive(): self
    {
        return new self(
            code: $this->code,
            name: $this->name,
            connectionId: $this->connectionId,
            queue: $this->queue,
            dispatchPoolCode: $this->dispatchPoolCode,
            applicationCode: $this->applicationCode,
            description: $this->description,
            clientScoped: $this->clientScoped,
            eventTypeCode: $this->eventTypeCode,
            maxRetries: $this->maxRetries,
            retryDelaySeconds: $this->retryDelaySeconds,
            timeoutSeconds: $this->timeoutSeconds,
            active: false,
        );
    }

    /**
     * Convert to array for the sync API.
     *
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        $data = [
            'code' => $this->code,
            'name' => $this->name,
            'connectionId' => $this->connectionId,
            'queue' => $this->queue,
            'dispatchPoolCode' => $this->dispatchPoolCode,
            'clientScoped' => $this->clientScoped,
            'maxRetries' => $this->maxRetries,
            'retryDelaySeconds' => $this->retryDelaySeconds,
            'timeoutSeconds' => $this->timeoutSeconds,
            'active' => $this->active,
        ];

        if ($this->applicationCode !== null) {
            $data['applicationCode'] = $this->applicationCode;
        }

        if ($this->description !== null) {
            $data['description'] = $this->description;
        }

        if ($this->eventTypeCode !== null) {
            $data['eventTypeCode'] = $this->eventTypeCode;
        }

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
            name: $data['name'],
            connectionId: $data['connectionId'],
            queue: $data['queue'],
            dispatchPoolCode: $data['dispatchPoolCode'],
            applicationCode: $data['applicationCode'] ?? null,
            description: $data['description'] ?? null,
            clientScoped: $data['clientScoped'] ?? false,
            eventTypeCode: $data['eventTypeCode'] ?? null,
            maxRetries: $data['maxRetries'] ?? 3,
            retryDelaySeconds: $data['retryDelaySeconds'] ?? 60,
            timeoutSeconds: $data['timeoutSeconds'] ?? 30,
            active: $data['active'] ?? true,
        );
    }
}
