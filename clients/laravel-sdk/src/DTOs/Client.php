<?php

declare(strict_types=1);

namespace FlowCatalyst\DTOs;

use FlowCatalyst\Generated\Model\ClientDto;

class Client
{
    public function __construct(
        public readonly string $id,
        public readonly string $name,
        public readonly string $identifier,
        public readonly string $status,
        public readonly ?string $statusReason,
        public readonly ?string $statusChangedAt,
        public readonly string $createdAt,
        public readonly string $updatedAt,
    ) {}

    public static function fromGenerated(ClientDto $dto): self
    {
        return new self(
            id: $dto->getId(),
            name: $dto->getName(),
            identifier: $dto->getIdentifier(),
            status: $dto->getStatus() ?? 'ACTIVE',
            statusReason: $dto->getStatusReason(),
            statusChangedAt: $dto->getStatusChangedAt()?->format('c'),
            createdAt: $dto->getCreatedAt()?->format('c') ?? '',
            updatedAt: $dto->getUpdatedAt()?->format('c') ?? '',
        );
    }

    public static function fromArray(array $data): self
    {
        return new self(
            id: $data['id'],
            name: $data['name'],
            identifier: $data['identifier'],
            status: $data['status'] ?? 'ACTIVE',
            statusReason: $data['statusReason'] ?? null,
            statusChangedAt: $data['statusChangedAt'] ?? null,
            createdAt: $data['createdAt'] ?? '',
            updatedAt: $data['updatedAt'] ?? '',
        );
    }

    public function toArray(): array
    {
        return [
            'id' => $this->id,
            'name' => $this->name,
            'identifier' => $this->identifier,
            'status' => $this->status,
            'statusReason' => $this->statusReason,
            'statusChangedAt' => $this->statusChangedAt,
            'createdAt' => $this->createdAt,
            'updatedAt' => $this->updatedAt,
        ];
    }
}
