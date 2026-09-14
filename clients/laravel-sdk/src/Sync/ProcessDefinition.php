<?php

declare(strict_types=1);

namespace FlowCatalyst\Sync;

/**
 * Represents a process documentation definition for syncing to FlowCatalyst.
 *
 * Can be used directly or converted to array for the sync API. Mirrors
 * `EventTypeDefinition` but uses a 3-segment code (`application:subdomain:processName`)
 * instead of 4, and carries the diagram body verbatim.
 */
final class ProcessDefinition
{
    /**
     * @param string $name Human-readable name
     * @param string $application Application segment (e.g., 'orders')
     * @param string $subdomain Subdomain segment (e.g., 'fulfilment')
     * @param string $processName Process slug (e.g., 'shipment-flow')
     * @param string $body Diagram source — typically Mermaid. Stored verbatim.
     * @param string|null $description Short summary
     * @param string $diagramType Defaults to 'mermaid'
     * @param string[] $tags Optional tags for grouping
     */
    public function __construct(
        public readonly string $name,
        public readonly string $application,
        public readonly string $subdomain,
        public readonly string $processName,
        public readonly string $body = '',
        public readonly ?string $description = null,
        public readonly string $diagramType = 'mermaid',
        public readonly array $tags = [],
    ) {}

    /**
     * Get the full code in FlowCatalyst format:
     * `{application}:{subdomain}:{processName}`
     */
    public function getCode(): string
    {
        return "{$this->application}:{$this->subdomain}:{$this->processName}";
    }

    /**
     * Create a new process definition with fluent syntax.
     *
     * @param string[] $tags
     */
    public static function make(
        string $name,
        string $application,
        string $subdomain,
        string $processName,
        string $body = '',
        array $tags = [],
    ): self {
        return new self(
            name: $name,
            application: $application,
            subdomain: $subdomain,
            processName: $processName,
            body: $body,
            tags: $tags,
        );
    }

    /**
     * Create a copy with a different description.
     */
    public function withDescription(string $description): self
    {
        return new self(
            name: $this->name,
            application: $this->application,
            subdomain: $this->subdomain,
            processName: $this->processName,
            body: $this->body,
            description: $description,
            diagramType: $this->diagramType,
            tags: $this->tags,
        );
    }

    /**
     * Create a copy with a different diagram body.
     */
    public function withBody(string $body): self
    {
        return new self(
            name: $this->name,
            application: $this->application,
            subdomain: $this->subdomain,
            processName: $this->processName,
            body: $body,
            description: $this->description,
            diagramType: $this->diagramType,
            tags: $this->tags,
        );
    }

    /**
     * Convert to array for the sync API.
     *
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        $data = [
            'code' => $this->getCode(),
            'name' => $this->name,
            'body' => $this->body,
            'diagramType' => $this->diagramType,
            'tags' => $this->tags,
        ];

        if ($this->description !== null) {
            $data['description'] = $this->description;
        }

        return $data;
    }

    /**
     * Create from array (e.g., from cached definitions).
     *
     * @param array<string, mixed> $data
     */
    public static function fromArray(array $data): self
    {
        return new self(
            name: $data['name'],
            application: $data['application'],
            subdomain: $data['subdomain'],
            processName: $data['processName'],
            body: $data['body'] ?? '',
            description: $data['description'] ?? null,
            diagramType: $data['diagramType'] ?? 'mermaid',
            tags: $data['tags'] ?? [],
        );
    }
}
