/**
 * DTO for creating an audit log entry in the outbox.
 *
 * Uses an immutable builder pattern - all `with*()` methods return a new instance.
 *
 * @example
 * ```typescript
 * const auditLog = CreateAuditLogDto
 *   .create('User', '0HZXEQ5Y8JY5Z', 'CREATE')
 *   .withOperationData({ email: 'user@example.com', name: 'John' })
 *   .withPrincipalId('0HZXEQ5Y8JY5A')
 *   .withSource('user-service');
 * ```
 */
export class CreateAuditLogDto {
	readonly entityType: string;
	readonly entityId: string;
	readonly operation: string;
	readonly operationData: Record<string, unknown> | null;
	readonly principalId: string | null;
	readonly performedAt: Date | null;
	readonly source: string | null;
	readonly correlationId: string | null;
	/** FlowCatalyst application (by code) this audit log belongs to; resolved to an application_id at ingest. */
	readonly applicationCode: string | null;
	/** FlowCatalyst client (by code) this audit log belongs to; resolved to a client_id at ingest. */
	readonly clientCode: string | null;
	readonly metadata: Record<string, string>;
	readonly headers: Record<string, string>;

	private constructor(params: {
		entityType: string;
		entityId: string;
		operation: string;
		operationData?: Record<string, unknown> | null;
		principalId?: string | null;
		performedAt?: Date | null;
		source?: string | null;
		correlationId?: string | null;
		applicationCode?: string | null;
		clientCode?: string | null;
		metadata?: Record<string, string>;
		headers?: Record<string, string>;
	}) {
		this.entityType = params.entityType;
		this.entityId = params.entityId;
		this.operation = params.operation;
		this.operationData = params.operationData ?? null;
		this.principalId = params.principalId ?? null;
		this.performedAt = params.performedAt ?? null;
		this.source = params.source ?? null;
		this.correlationId = params.correlationId ?? null;
		this.applicationCode = params.applicationCode ?? null;
		this.clientCode = params.clientCode ?? null;
		this.metadata = params.metadata ?? {};
		this.headers = params.headers ?? {};
	}

	static create(
		entityType: string,
		entityId: string,
		operation: string,
	): CreateAuditLogDto {
		return new CreateAuditLogDto({ entityType, entityId, operation });
	}

	withOperationData(operationData: Record<string, unknown>): CreateAuditLogDto {
		return new CreateAuditLogDto({ ...this.toParams(), operationData });
	}

	withPrincipalId(principalId: string): CreateAuditLogDto {
		return new CreateAuditLogDto({ ...this.toParams(), principalId });
	}

	withPerformedAt(performedAt: Date): CreateAuditLogDto {
		return new CreateAuditLogDto({ ...this.toParams(), performedAt });
	}

	withSource(source: string): CreateAuditLogDto {
		return new CreateAuditLogDto({ ...this.toParams(), source });
	}

	withCorrelationId(correlationId: string): CreateAuditLogDto {
		return new CreateAuditLogDto({ ...this.toParams(), correlationId });
	}

	/** Set the FlowCatalyst application (by code) this audit log belongs to. */
	withApplicationCode(applicationCode: string): CreateAuditLogDto {
		return new CreateAuditLogDto({ ...this.toParams(), applicationCode });
	}

	/** Set the FlowCatalyst client (by code) this audit log belongs to. */
	withClientCode(clientCode: string): CreateAuditLogDto {
		return new CreateAuditLogDto({ ...this.toParams(), clientCode });
	}

	withMetadata(metadata: Record<string, string>): CreateAuditLogDto {
		return new CreateAuditLogDto({
			...this.toParams(),
			metadata: { ...this.metadata, ...metadata },
		});
	}

	withHeaders(headers: Record<string, string>): CreateAuditLogDto {
		return new CreateAuditLogDto({
			...this.toParams(),
			headers: { ...this.headers, ...headers },
		});
	}

	/** Build the audit log payload for the outbox. Filters out null values. */
	toPayload(): Record<string, unknown> {
		return filterNulls({
			entityType: this.entityType,
			entityId: this.entityId,
			operation: this.operation,
			operationData: this.operationData
				? JSON.stringify(this.operationData)
				: null,
			principalId: this.principalId,
			performedAt: (this.performedAt ?? new Date()).toISOString(),
			source: this.source,
			correlationId: this.correlationId,
			applicationCode: this.applicationCode,
			clientCode: this.clientCode,
			metadata: Object.keys(this.metadata).length > 0 ? this.metadata : null,
		});
	}

	private toParams() {
		return {
			entityType: this.entityType,
			entityId: this.entityId,
			operation: this.operation,
			operationData: this.operationData,
			principalId: this.principalId,
			performedAt: this.performedAt,
			source: this.source,
			correlationId: this.correlationId,
			applicationCode: this.applicationCode,
			clientCode: this.clientCode,
			metadata: this.metadata,
			headers: this.headers,
		};
	}
}

function filterNulls(obj: Record<string, unknown>): Record<string, unknown> {
	const result: Record<string, unknown> = {};
	for (const [key, value] of Object.entries(obj)) {
		if (value !== null && value !== undefined) {
			result[key] = value;
		}
	}
	return result;
}
