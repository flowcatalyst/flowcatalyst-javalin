<?php

declare(strict_types=1);

namespace FlowCatalyst\DTOs;

final class ScheduledJobInstanceLog
{
    public function __construct(
        public readonly string $id,
        public readonly string $instanceId,
        public readonly string $level,
        public readonly string $message,
        public readonly mixed $metadata,
        public readonly string $createdAt,
    ) {}

    /**
     * @param array<string, mixed> $data
     */
    public static function fromArray(array $data): self
    {
        return new self(
            id: (string) $data['id'],
            instanceId: (string) $data['instanceId'],
            level: (string) ($data['level'] ?? 'INFO'),
            message: (string) $data['message'],
            metadata: $data['metadata'] ?? null,
            createdAt: (string) $data['createdAt'],
        );
    }
}
