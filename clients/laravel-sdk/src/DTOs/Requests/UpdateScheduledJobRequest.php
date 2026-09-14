<?php

declare(strict_types=1);

namespace FlowCatalyst\DTOs\Requests;

/**
 * Partial update — only non-null fields are sent.
 */
final class UpdateScheduledJobRequest
{
    /**
     * @param string[]|null $crons
     */
    public function __construct(
        public readonly ?string $name = null,
        public readonly ?string $description = null,
        public readonly ?array $crons = null,
        public readonly ?string $timezone = null,
        public readonly mixed $payload = null,
        public readonly ?bool $concurrent = null,
        public readonly ?bool $tracksCompletion = null,
        public readonly ?int $timeoutSeconds = null,
        public readonly ?int $deliveryMaxAttempts = null,
        public readonly ?string $targetUrl = null,
    ) {}

    /**
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        $out = [];
        if ($this->name !== null) $out['name'] = $this->name;
        if ($this->description !== null) $out['description'] = $this->description;
        if ($this->crons !== null) $out['crons'] = $this->crons;
        if ($this->timezone !== null) $out['timezone'] = $this->timezone;
        if ($this->payload !== null) $out['payload'] = $this->payload;
        if ($this->concurrent !== null) $out['concurrent'] = $this->concurrent;
        if ($this->tracksCompletion !== null) $out['tracksCompletion'] = $this->tracksCompletion;
        if ($this->timeoutSeconds !== null) $out['timeoutSeconds'] = $this->timeoutSeconds;
        if ($this->deliveryMaxAttempts !== null) $out['deliveryMaxAttempts'] = $this->deliveryMaxAttempts;
        if ($this->targetUrl !== null) $out['targetUrl'] = $this->targetUrl;
        return $out;
    }
}
