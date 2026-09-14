<?php

declare(strict_types=1);

namespace FlowCatalyst\DTOs;

/**
 * Application enabled for a client the user has access to.
 */
class MyApplication
{
    public function __construct(
        public readonly string $id,
        public readonly string $code,
        public readonly string $name,
        public readonly ?string $description,
        public readonly ?string $iconUrl,
        public readonly ?string $baseUrl,
        public readonly ?string $website,
        public readonly ?string $logoMimeType,
    ) {}

    public static function fromArray(array $data): self
    {
        return new self(
            id: $data['id'],
            code: $data['code'],
            name: $data['name'],
            description: $data['description'] ?? null,
            iconUrl: $data['iconUrl'] ?? null,
            baseUrl: $data['baseUrl'] ?? null,
            website: $data['website'] ?? null,
            logoMimeType: $data['logoMimeType'] ?? null,
        );
    }

    public function toArray(): array
    {
        return [
            'id' => $this->id,
            'code' => $this->code,
            'name' => $this->name,
            'description' => $this->description,
            'iconUrl' => $this->iconUrl,
            'baseUrl' => $this->baseUrl,
            'website' => $this->website,
            'logoMimeType' => $this->logoMimeType,
        ];
    }
}
