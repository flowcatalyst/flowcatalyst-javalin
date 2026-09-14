<?php

declare(strict_types=1);

namespace FlowCatalyst\DTOs;

use FlowCatalyst\Enums\ScheduledJobInstanceStatus;

final class ScheduledJobInstance
{
    public function __construct(
        public readonly string $id,
        public readonly string $scheduledJobId,
        public readonly ?string $clientId,
        public readonly string $jobCode,
        public readonly string $triggerKind,
        public readonly ?string $scheduledFor,
        public readonly string $firedAt,
        public readonly ?string $deliveredAt,
        public readonly ?string $completedAt,
        public readonly ScheduledJobInstanceStatus $status,
        public readonly int $deliveryAttempts,
        public readonly ?string $deliveryError,
        public readonly ?string $completionStatus,
        public readonly mixed $completionResult,
        public readonly ?string $correlationId,
        public readonly string $createdAt,
    ) {}

    /**
     * @param array<string, mixed> $data
     */
    public static function fromArray(array $data): self
    {
        return new self(
            id: (string) $data['id'],
            scheduledJobId: (string) $data['scheduledJobId'],
            clientId: isset($data['clientId']) ? (string) $data['clientId'] : null,
            jobCode: (string) $data['jobCode'],
            triggerKind: (string) ($data['triggerKind'] ?? 'CRON'),
            scheduledFor: isset($data['scheduledFor']) ? (string) $data['scheduledFor'] : null,
            firedAt: (string) $data['firedAt'],
            deliveredAt: isset($data['deliveredAt']) ? (string) $data['deliveredAt'] : null,
            completedAt: isset($data['completedAt']) ? (string) $data['completedAt'] : null,
            status: ScheduledJobInstanceStatus::tryFrom((string) ($data['status'] ?? 'QUEUED'))
                ?? ScheduledJobInstanceStatus::QUEUED,
            deliveryAttempts: (int) ($data['deliveryAttempts'] ?? 0),
            deliveryError: isset($data['deliveryError']) ? (string) $data['deliveryError'] : null,
            completionStatus: isset($data['completionStatus']) ? (string) $data['completionStatus'] : null,
            completionResult: $data['completionResult'] ?? null,
            correlationId: isset($data['correlationId']) ? (string) $data['correlationId'] : null,
            createdAt: (string) $data['createdAt'],
        );
    }
}
