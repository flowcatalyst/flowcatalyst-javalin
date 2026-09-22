import { apiFetch } from "./client";
import type {
	AliasResponse,
	ClaimRequest,
	ConfigResponse,
	CreateFunctionRequest,
	DomainResponse,
	FunctionPageResponse,
	FunctionResponse,
	FunctionRouteResponse,
	PolicyResponse,
	PoolSummaryResponse,
	PromoteRequest,
	PromoteResponse,
	PublishRequest,
	PublishResponse,
	PutPolicyRequest,
	SecretListResponse,
	SetConfigRequest,
	SetSecretRequest,
	StatusResponse,
	UpdateFunctionRequest,
	UploadArtifactResponse,
	VersionResponse,
} from "./generated-functions";

// Response/request types alias the generated contract
// (../server/src/main/resources/openapi/functions.openapi.json) so `vue-tsc`
// fails on backend drift — same convention as api/dispatch-pools.ts. Every
// operation of the function document is wrapped below EXCEPT the four
// `/control/functions/*` routes (getDesiredState, heartbeatFunctionHost,
// emitFunctionEvents, downloadFunctionArtifact): those are the function
// host's own protocol to the platform, not something the SPA ever calls.
export type {
	AliasResponse,
	ConfigResponse,
	DomainResponse,
	FunctionPageResponse,
	FunctionResponse,
	FunctionRouteResponse,
	PolicyResponse,
	PoolSummaryResponse,
	PromoteResponse,
	PublishResponse,
	SecretListResponse,
	StatusResponse,
	UploadArtifactResponse,
	VersionResponse,
};

export type FunctionStatus = "ACTIVE" | "DISABLED";

export interface FunctionListFilters {
	/** A `FunctionAddressPattern`: exact `a.b.c`, `a.b.*`, or `a.*` (application-only). */
	address?: string;
	/** An owning client id, or `platform`. */
	clientId?: string;
	status?: FunctionStatus;
	page?: number;
	size?: number;
}

export interface FunctionRouteFilters {
	hostname?: string;
	address?: string;
}

export const functionsApi = {
	list(filters: FunctionListFilters = {}): Promise<FunctionPageResponse> {
		const params = new URLSearchParams();
		if (filters.address) params.set("address", filters.address);
		if (filters.clientId) params.set("clientId", filters.clientId);
		if (filters.status) params.set("status", filters.status);
		if (filters.page !== undefined) params.set("page", String(filters.page));
		if (filters.size !== undefined) params.set("size", String(filters.size));
		const query = params.toString();
		return apiFetch(`/functions${query ? `?${query}` : ""}`);
	},

	create(data: CreateFunctionRequest): Promise<FunctionResponse> {
		return apiFetch("/functions", {
			method: "POST",
			body: JSON.stringify(data),
		});
	},

	get(address: string): Promise<FunctionResponse> {
		return apiFetch(`/functions/${encodeURIComponent(address)}`);
	},

	/** 204 No Content on the wire — reload the function from `get` after. */
	update(address: string, data: UpdateFunctionRequest): Promise<void> {
		return apiFetch(`/functions/${encodeURIComponent(address)}`, {
			method: "PUT",
			body: JSON.stringify(data),
		});
	},

	delete(address: string): Promise<void> {
		return apiFetch(`/functions/${encodeURIComponent(address)}`, {
			method: "DELETE",
		});
	},

	status(address: string): Promise<StatusResponse> {
		return apiFetch(`/functions/${encodeURIComponent(address)}/status`);
	},

	pools(): Promise<PoolSummaryResponse[]> {
		return apiFetch("/function-pools");
	},

	publishVersion(
		address: string,
		data: PublishRequest,
	): Promise<PublishResponse> {
		return apiFetch(`/functions/${encodeURIComponent(address)}/versions`, {
			method: "POST",
			body: JSON.stringify(data),
		});
	},

	listVersions(address: string): Promise<VersionResponse[]> {
		return apiFetch(`/functions/${encodeURIComponent(address)}/versions`);
	},

	getVersion(address: string, version: number): Promise<VersionResponse> {
		return apiFetch(
			`/functions/${encodeURIComponent(address)}/versions/${version}`,
		);
	},

	retireVersion(address: string, version: number): Promise<VersionResponse> {
		return apiFetch(
			`/functions/${encodeURIComponent(address)}/versions/${version}/retire`,
			{ method: "POST" },
		);
	},

	promoteAlias(
		address: string,
		alias: string,
		data: PromoteRequest,
	): Promise<PromoteResponse> {
		return apiFetch(
			`/functions/${encodeURIComponent(address)}/aliases/${encodeURIComponent(alias)}`,
			{ method: "PUT", body: JSON.stringify(data) },
		);
	},

	listAliases(address: string): Promise<AliasResponse[]> {
		return apiFetch(`/functions/${encodeURIComponent(address)}/aliases`);
	},

	/**
	 * Raw-bytes upload: the server takes `application/octet-stream`, not
	 * JSON. `apiFetch` only defaults to a JSON content-type when the caller
	 * hasn't already set one (see the minimal capability added to
	 * api/client.ts for this) — passing our own header here sends the body
	 * as-is instead of JSON-stringifying it.
	 */
	uploadArtifact(
		address: string,
		digest: string,
		bytes: BodyInit,
	): Promise<UploadArtifactResponse> {
		return apiFetch(
			`/functions/${encodeURIComponent(address)}/artifacts/${encodeURIComponent(digest)}`,
			{
				method: "PUT",
				body: bytes,
				headers: { "Content-Type": "application/octet-stream" },
			},
		);
	},

	getConfig(address: string): Promise<ConfigResponse> {
		return apiFetch(`/functions/${encodeURIComponent(address)}/config`);
	},

	setConfig(
		address: string,
		data: SetConfigRequest,
	): Promise<ConfigResponse> {
		return apiFetch(`/functions/${encodeURIComponent(address)}/config`, {
			method: "PUT",
			body: JSON.stringify(data),
		});
	},

	listSecrets(address: string): Promise<SecretListResponse> {
		return apiFetch(`/functions/${encodeURIComponent(address)}/secrets`);
	},

	/** 204 No Content — the value is never returned, only "set"/"not set". */
	setSecret(
		address: string,
		key: string,
		data: SetSecretRequest,
	): Promise<void> {
		return apiFetch(
			`/functions/${encodeURIComponent(address)}/secrets/${encodeURIComponent(key)}`,
			{ method: "PUT", body: JSON.stringify(data) },
		);
	},

	deleteSecret(address: string, key: string): Promise<void> {
		return apiFetch(
			`/functions/${encodeURIComponent(address)}/secrets/${encodeURIComponent(key)}`,
			{ method: "DELETE" },
		);
	},

	getPolicy(owner: string): Promise<PolicyResponse> {
		return apiFetch(`/function-policies/${encodeURIComponent(owner)}`);
	},

	putPolicy(
		owner: string,
		data: PutPolicyRequest,
	): Promise<PolicyResponse> {
		return apiFetch(`/function-policies/${encodeURIComponent(owner)}`, {
			method: "PUT",
			body: JSON.stringify(data),
		});
	},

	claimDomain(data: ClaimRequest): Promise<DomainResponse> {
		return apiFetch("/function-domains", {
			method: "POST",
			body: JSON.stringify(data),
		});
	},

	/** `clientId` is required on the wire — pass `"platform"` for platform-owned domains. */
	listDomains(clientId: string): Promise<DomainResponse[]> {
		const params = new URLSearchParams({ clientId });
		return apiFetch(`/function-domains?${params.toString()}`);
	},

	verifyDomain(hostname: string): Promise<DomainResponse> {
		return apiFetch(
			`/function-domains/${encodeURIComponent(hostname)}/verify`,
			{ method: "POST" },
		);
	},

	releaseDomain(hostname: string): Promise<void> {
		return apiFetch(`/function-domains/${encodeURIComponent(hostname)}`, {
			method: "DELETE",
		});
	},

	listRoutes(
		filters: FunctionRouteFilters = {},
	): Promise<FunctionRouteResponse[]> {
		const params = new URLSearchParams();
		if (filters.hostname) params.set("hostname", filters.hostname);
		if (filters.address) params.set("address", filters.address);
		const query = params.toString();
		return apiFetch(`/function-routes${query ? `?${query}` : ""}`);
	},
};
