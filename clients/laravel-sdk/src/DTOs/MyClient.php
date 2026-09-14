<?php

declare(strict_types=1);

namespace FlowCatalyst\DTOs;

/**
 * Client the authenticated user has access to.
 */
class MyClient
{
    public function __construct(
        public readonly string $id,
        public readonly string $name,
        public readonly string $identifier,
        public readonly string $status,
        public readonly ?string $createdAt,
        public readonly ?string $updatedAt,
    ) {}

    public static function fromArray(array $data): self
    {
        return new self(
            id: $data['id'],
            name: $data['name'],
            identifier: $data['identifier'],
            status: $data['status'] ?? 'ACTIVE',
            createdAt: $data['createdAt'] ?? null,
            updatedAt: $data['updatedAt'] ?? null,
        );
    }

    public function toArray(): array
    {
        return [
            'id' => $this->id,
            'name' => $this->name,
            'identifier' => $this->identifier,
            'status' => $this->status,
            'createdAt' => $this->createdAt,
            'updatedAt' => $this->updatedAt,
        ];
    }
}
