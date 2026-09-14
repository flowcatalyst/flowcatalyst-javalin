<?php

declare(strict_types=1);

namespace FlowCatalyst\DTOs;

use FlowCatalyst\Enums\ProcessSource;
use FlowCatalyst\Enums\ProcessStatus;

/**
 * A process documentation record.
 *
 * The full `code` is formatted `{application}:{subdomain}:{processName}`,
 * and the parsed segments are exposed individually. The `body` field stores
 * the diagram source verbatim (typically Mermaid) — the platform never
 * inspects or transforms it.
 */
final class Process
{
    /**
     * @param string[] $tags
     */
    public function __construct(
        public readonly string $id,
        public readonly string $code,
        public readonly string $name,
        public readonly ProcessStatus $status,
        public readonly ProcessSource $source,
        public readonly string $application,
        public readonly string $subdomain,
        public readonly string $processName,
        public readonly string $body,
        public readonly string $diagramType,
        public readonly array $tags,
        public readonly string $createdAt,
        public readonly string $updatedAt,
        public readonly ?string $description = null,
    ) {}

    /**
     * @param array<string, mixed> $data
     */
    public static function fromArray(array $data): self
    {
        /** @var array<int, string> $tags */
        $tags = $data['tags'] ?? [];
        return new self(
            id: (string) $data['id'],
            code: (string) $data['code'],
            name: (string) $data['name'],
            status: ProcessStatus::tryFrom((string) ($data['status'] ?? 'CURRENT'))
                ?? ProcessStatus::CURRENT,
            source: ProcessSource::tryFrom((string) ($data['source'] ?? 'UI'))
                ?? ProcessSource::UI,
            application: (string) ($data['application'] ?? ''),
            subdomain: (string) ($data['subdomain'] ?? ''),
            processName: (string) ($data['processName'] ?? ''),
            body: (string) ($data['body'] ?? ''),
            diagramType: (string) ($data['diagramType'] ?? 'mermaid'),
            tags: array_map(fn($t) => (string) $t, $tags),
            createdAt: (string) ($data['createdAt'] ?? ''),
            updatedAt: (string) ($data['updatedAt'] ?? ''),
            description: isset($data['description']) ? (string) $data['description'] : null,
        );
    }

    /**
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        return [
            'id' => $this->id,
            'code' => $this->code,
            'name' => $this->name,
            'status' => $this->status->value,
            'source' => $this->source->value,
            'application' => $this->application,
            'subdomain' => $this->subdomain,
            'processName' => $this->processName,
            'body' => $this->body,
            'diagramType' => $this->diagramType,
            'tags' => $this->tags,
            'createdAt' => $this->createdAt,
            'updatedAt' => $this->updatedAt,
            'description' => $this->description,
        ];
    }
}
