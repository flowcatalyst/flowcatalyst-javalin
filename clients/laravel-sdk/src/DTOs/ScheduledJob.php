<?php

declare(strict_types=1);

namespace FlowCatalyst\DTOs;

use FlowCatalyst\Enums\ScheduledJobStatus;

/**
 * A ScheduledJob definition. Cron-fired or manually-triggered webhook job.
 */
final class ScheduledJob
{
    /**
     * @param string[] $crons
     */
    public function __construct(
        public readonly string $id,
        public readonly ?string $clientId,
        public readonly string $code,
        public readonly string $name,
        public readonly ScheduledJobStatus $status,
        public readonly array $crons,
        public readonly string $timezone,
        public readonly bool $concurrent,
        public readonly bool $tracksCompletion,
        public readonly int $deliveryMaxAttempts,
        public readonly bool $hasActiveInstance,
        public readonly string $createdAt,
        public readonly string $updatedAt,
        public readonly int $version,
        public readonly ?string $description = null,
        public readonly ?int $timeoutSeconds = null,
        public readonly ?string $targetUrl = null,
        public readonly ?string $lastFiredAt = null,
        public readonly mixed $payload = null,
        public readonly ?string $createdBy = null,
        public readonly ?string $updatedBy = null,
    ) {}

    /**
     * @param array<string, mixed> $data
     */
    public static function fromArray(array $data): self
    {
        return new self(
            id: (string) $data['id'],
            clientId: isset($data['clientId']) ? (string) $data['clientId'] : null,
            code: (string) $data['code'],
            name: (string) $data['name'],
            status: ScheduledJobStatus::tryFrom((string) ($data['status'] ?? 'ACTIVE'))
                ?? ScheduledJobStatus::ACTIVE,
            crons: array_map('strval', (array) ($data['crons'] ?? [])),
            timezone: (string) ($data['timezone'] ?? 'UTC'),
            concurrent: (bool) ($data['concurrent'] ?? false),
            tracksCompletion: (bool) ($data['tracksCompletion'] ?? false),
            deliveryMaxAttempts: (int) ($data['deliveryMaxAttempts'] ?? 3),
            hasActiveInstance: (bool) ($data['hasActiveInstance'] ?? false),
            createdAt: (string) $data['createdAt'],
            updatedAt: (string) $data['updatedAt'],
            version: (int) ($data['version'] ?? 1),
            description: isset($data['description']) ? (string) $data['description'] : null,
            timeoutSeconds: isset($data['timeoutSeconds']) ? (int) $data['timeoutSeconds'] : null,
            targetUrl: isset($data['targetUrl']) ? (string) $data['targetUrl'] : null,
            lastFiredAt: isset($data['lastFiredAt']) ? (string) $data['lastFiredAt'] : null,
            payload: $data['payload'] ?? null,
            createdBy: isset($data['createdBy']) ? (string) $data['createdBy'] : null,
            updatedBy: isset($data['updatedBy']) ? (string) $data['updatedBy'] : null,
        );
    }
}
