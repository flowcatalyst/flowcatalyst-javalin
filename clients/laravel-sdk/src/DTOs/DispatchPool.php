<?php

declare(strict_types=1);

namespace FlowCatalyst\DTOs;

use FlowCatalyst\Enums\DispatchPoolStatus;

class DispatchPool
{
    public function __construct(
        public readonly string $id,
        public readonly string $code,
        public readonly string $name,
        public readonly ?string $description,
        public readonly int $rateLimit,
        public readonly int $concurrency,
        public readonly ?string $clientId,
        public readonly ?string $clientIdentifier,
        public readonly DispatchPoolStatus $status,
        public readonly string $createdAt,
        public readonly string $updatedAt,
    ) {}

    public static function fromArray(array $data): self
    {
        return new self(
            id: $data['id'],
            code: $data['code'],
            name: $data['name'],
            description: $data['description'] ?? null,
            rateLimit: $data['rateLimit'] ?? 100,
            concurrency: $data['concurrency'] ?? 10,
            clientId: $data['clientId'] ?? null,
            clientIdentifier: $data['clientIdentifier'] ?? null,
            status: DispatchPoolStatus::tryFrom($data['status'] ?? 'ACTIVE') ?? DispatchPoolStatus::ACTIVE,
            createdAt: $data['createdAt'] ?? '',
            updatedAt: $data['updatedAt'] ?? '',
        );
    }

    public function toArray(): array
    {
        return [
            'id' => $this->id,
            'code' => $this->code,
            'name' => $this->name,
            'description' => $this->description,
            'rateLimit' => $this->rateLimit,
            'concurrency' => $this->concurrency,
            'clientId' => $this->clientId,
            'clientIdentifier' => $this->clientIdentifier,
            'status' => $this->status->value,
            'createdAt' => $this->createdAt,
            'updatedAt' => $this->updatedAt,
        ];
    }
}
