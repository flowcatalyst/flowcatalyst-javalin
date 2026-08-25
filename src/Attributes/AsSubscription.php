<?php

declare(strict_types=1);

namespace FlowCatalyst\Attributes;

use Attribute;
use FlowCatalyst\Enums\DispatchMode;

/**
 * Marks a class as a subscription definition for FlowCatalyst.
 *
 * Usage:
 * ```php
 * #[AsSubscription(
 *     code: 'order-processor',
 *     name: 'Order Processor',
 *     description: 'Processes new orders',
 *     clientScoped: true,  // Set to true for client-scoped subscriptions
 *     eventTypes: ['myapp:orders:order:created', 'myapp:orders:order:updated'],
 *     connectionId: 'conn_abc123',
 *     queue: 'orders',
 *     dispatchPoolCode: 'default',
 *     mode: DispatchMode::BLOCK_ON_ERROR
 * )]
 * class OrderProcessorSubscription {}
 * ```
 */
#[Attribute(Attribute::TARGET_CLASS)]
final class AsSubscription
{
    /**
     * @param string $code Unique subscription code
     * @param string $name Human-friendly name
     * @param string $connectionId Connection ID for dispatch routing
     * @param string $queue Queue name for message routing
     * @param string $dispatchPoolCode Dispatch pool code for rate limiting
     * @param string|null $description Subscription description
     * @param bool $clientScoped Whether this subscription is client-scoped
     * @param string[] $eventTypes Array of event type codes to subscribe to
     * @param array<array{key: string, value: string}> $customConfig Custom configuration entries
     * @param int|null $maxAgeSeconds Maximum age in seconds for dispatch jobs
     * @param int|null $delaySeconds Delay before first dispatch attempt
     * @param int|null $sequence Sequence number for ordering
     * @param DispatchMode|string|null $mode Dispatch mode (IMMEDIATE or BLOCK_ON_ERROR)
     * @param int|null $timeoutSeconds Timeout for target response
     * @param int|null $maxRetries Maximum retry attempts
     * @param bool|null $dataOnly If true, only send event data (no envelope)
     * @param string|null $application Application code this subscription belongs
     *        to. Overrides the global `application_code` / `application_map`
     *        during sync — set it when one codebase defines subscriptions for
     *        more than one application. Null = resolve from the namespace map /
     *        default.
     */
    public function __construct(
        public readonly string $code,
        public readonly string $name,
        public readonly string $connectionId,
        public readonly string $queue,
        public readonly string $dispatchPoolCode,
        public readonly ?string $description = null,
        public readonly bool $clientScoped = false,
        public readonly array $eventTypes = [],
        public readonly array $customConfig = [],
        public readonly ?int $maxAgeSeconds = null,
        public readonly ?int $delaySeconds = null,
        public readonly ?int $sequence = null,
        public readonly DispatchMode|string|null $mode = null,
        public readonly ?int $timeoutSeconds = null,
        public readonly ?int $maxRetries = null,
        public readonly ?bool $dataOnly = null,
        public readonly ?string $application = null,
    ) {}

    /**
     * Convert to array format for API sync.
     */
    public function toArray(): array
    {
        $mode = $this->mode;
        if ($mode instanceof DispatchMode) {
            $mode = $mode->value;
        }

        // Convert event types to binding format
        $eventTypeBindings = array_map(
            fn(string $code) => ['eventTypeCode' => $code],
            $this->eventTypes
        );

        $data = [
            'code' => $this->code,
            'name' => $this->name,
            'clientScoped' => $this->clientScoped,
            'eventTypes' => $eventTypeBindings ?: null,
            'connectionId' => $this->connectionId,
            'queue' => $this->queue,
            'dispatchPoolCode' => $this->dispatchPoolCode,
        ];

        if ($this->description !== null) {
            $data['description'] = $this->description;
        }
        if (!empty($this->customConfig)) {
            $data['customConfig'] = $this->customConfig;
        }
        if ($this->maxAgeSeconds !== null) {
            $data['maxAgeSeconds'] = $this->maxAgeSeconds;
        }
        if ($this->delaySeconds !== null) {
            $data['delaySeconds'] = $this->delaySeconds;
        }
        if ($this->sequence !== null) {
            $data['sequence'] = $this->sequence;
        }
        if ($mode !== null) {
            $data['mode'] = $mode;
        }
        if ($this->timeoutSeconds !== null) {
            $data['timeoutSeconds'] = $this->timeoutSeconds;
        }
        if ($this->maxRetries !== null) {
            $data['maxRetries'] = $this->maxRetries;
        }
        if ($this->dataOnly !== null) {
            $data['dataOnly'] = $this->dataOnly;
        }

        return $data;
    }
}
