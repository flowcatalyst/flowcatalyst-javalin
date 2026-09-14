<?php

declare(strict_types=1);

namespace FlowCatalyst\Outbox\DTOs;

/**
 * DTO for creating an audit log entry in the outbox.
 */
class CreateAuditLogDto
{
    /**
     * @param array<string, mixed>|null $operationData Operation payload data
     * @param array<string, string> $metadata Additional metadata
     * @param array<string, string> $headers Optional headers
     */
    public function __construct(
        public readonly string $entityType,
        public readonly string $entityId,
        public readonly string $operation,
        public readonly ?array $operationData = null,
        public readonly ?string $principalId = null,
        public readonly ?\DateTimeInterface $performedAt = null,
        public readonly ?string $source = null,
        public readonly ?string $correlationId = null,
        public readonly ?string $applicationCode = null,
        public readonly ?string $clientCode = null,
        public readonly array $metadata = [],
        public readonly array $headers = [],
    ) {}

    /**
     * Create a new audit log DTO.
     */
    public static function create(
        string $entityType,
        string $entityId,
        string $operation,
    ): self {
        return new self(
            entityType: $entityType,
            entityId: $entityId,
            operation: $operation,
        );
    }

    /**
     * Add operation data.
     */
    public function withOperationData(array $operationData): self
    {
        return new self(
            entityType: $this->entityType,
            entityId: $this->entityId,
            operation: $this->operation,
            operationData: $operationData,
            principalId: $this->principalId,
            performedAt: $this->performedAt,
            source: $this->source,
            correlationId: $this->correlationId,
            applicationCode: $this->applicationCode,
            clientCode: $this->clientCode,
            metadata: $this->metadata,
            headers: $this->headers,
        );
    }

    /**
     * Add a principal ID.
     */
    public function withPrincipalId(string $principalId): self
    {
        return new self(
            entityType: $this->entityType,
            entityId: $this->entityId,
            operation: $this->operation,
            operationData: $this->operationData,
            principalId: $principalId,
            performedAt: $this->performedAt,
            source: $this->source,
            correlationId: $this->correlationId,
            applicationCode: $this->applicationCode,
            clientCode: $this->clientCode,
            metadata: $this->metadata,
            headers: $this->headers,
        );
    }

    /**
     * Set when the operation was performed.
     */
    public function withPerformedAt(\DateTimeInterface $performedAt): self
    {
        return new self(
            entityType: $this->entityType,
            entityId: $this->entityId,
            operation: $this->operation,
            operationData: $this->operationData,
            principalId: $this->principalId,
            performedAt: $performedAt,
            source: $this->source,
            correlationId: $this->correlationId,
            applicationCode: $this->applicationCode,
            clientCode: $this->clientCode,
            metadata: $this->metadata,
            headers: $this->headers,
        );
    }

    /**
     * Add a source.
     */
    public function withSource(string $source): self
    {
        return new self(
            entityType: $this->entityType,
            entityId: $this->entityId,
            operation: $this->operation,
            operationData: $this->operationData,
            principalId: $this->principalId,
            performedAt: $this->performedAt,
            source: $source,
            correlationId: $this->correlationId,
            applicationCode: $this->applicationCode,
            clientCode: $this->clientCode,
            metadata: $this->metadata,
            headers: $this->headers,
        );
    }

    /**
     * Add a correlation ID.
     */
    public function withCorrelationId(string $correlationId): self
    {
        return new self(
            entityType: $this->entityType,
            entityId: $this->entityId,
            operation: $this->operation,
            operationData: $this->operationData,
            principalId: $this->principalId,
            performedAt: $this->performedAt,
            source: $this->source,
            correlationId: $correlationId,
            applicationCode: $this->applicationCode,
            clientCode: $this->clientCode,
            metadata: $this->metadata,
            headers: $this->headers,
        );
    }

    /**
     * Set the FlowCatalyst application (by code) this audit log belongs to. The
     * platform resolves the code to an application id at ingest.
     */
    public function withApplicationCode(string $applicationCode): self
    {
        return new self(
            entityType: $this->entityType,
            entityId: $this->entityId,
            operation: $this->operation,
            operationData: $this->operationData,
            principalId: $this->principalId,
            performedAt: $this->performedAt,
            source: $this->source,
            correlationId: $this->correlationId,
            applicationCode: $applicationCode,
            clientCode: $this->clientCode,
            metadata: $this->metadata,
            headers: $this->headers,
        );
    }

    /**
     * Set the FlowCatalyst client (by code) this audit log belongs to. The
     * platform resolves the code to a client id at ingest.
     */
    public function withClientCode(string $clientCode): self
    {
        return new self(
            entityType: $this->entityType,
            entityId: $this->entityId,
            operation: $this->operation,
            operationData: $this->operationData,
            principalId: $this->principalId,
            performedAt: $this->performedAt,
            source: $this->source,
            correlationId: $this->correlationId,
            applicationCode: $this->applicationCode,
            clientCode: $clientCode,
            metadata: $this->metadata,
            headers: $this->headers,
        );
    }

    /**
     * Add metadata.
     */
    public function withMetadata(array $metadata): self
    {
        return new self(
            entityType: $this->entityType,
            entityId: $this->entityId,
            operation: $this->operation,
            operationData: $this->operationData,
            principalId: $this->principalId,
            performedAt: $this->performedAt,
            source: $this->source,
            correlationId: $this->correlationId,
            applicationCode: $this->applicationCode,
            clientCode: $this->clientCode,
            metadata: array_merge($this->metadata, $metadata),
            headers: $this->headers,
        );
    }

    /**
     * Add headers.
     */
    public function withHeaders(array $headers): self
    {
        return new self(
            entityType: $this->entityType,
            entityId: $this->entityId,
            operation: $this->operation,
            operationData: $this->operationData,
            principalId: $this->principalId,
            performedAt: $this->performedAt,
            source: $this->source,
            correlationId: $this->correlationId,
            applicationCode: $this->applicationCode,
            clientCode: $this->clientCode,
            metadata: $this->metadata,
            headers: array_merge($this->headers, $headers),
        );
    }

    /**
     * Build the audit log payload for the outbox.
     */
    public function toPayload(): array
    {
        return array_filter([
            'entityType' => $this->entityType,
            'entityId' => $this->entityId,
            'operation' => $this->operation,
            'operationData' => $this->operationData !== null ? json_encode($this->operationData) : null,
            'principalId' => $this->principalId,
            'performedAt' => ($this->performedAt ?? new \DateTimeImmutable())->format('c'),
            'source' => $this->source,
            'correlationId' => $this->correlationId,
            'applicationCode' => $this->applicationCode,
            'clientCode' => $this->clientCode,
            'metadata' => !empty($this->metadata) ? $this->metadata : null,
        ], fn($v) => $v !== null);
    }
}
