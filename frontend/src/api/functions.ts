import { apiFetch, type FetchOptions } from "./client";
import type {
	AliasResponse,
	CheckManifestRequest,
	CheckManifestResponse,
	ClaimRequest,
	ConfigResponse,
	ConflictResponse,
	CreateFunctionRequest,
	DomainResponse,
	FunctionPageResponse,
	FunctionResponse,
	FunctionRouteResponse,
	Manifest,
	ManifestErrorResponse,
	PolicyListResponse,
	PolicyResponse,
	PolicySignerRequest,
	PoolActionResponse,
	PoolSummaryResponse,
	PromotePlanResponse,
	PromoteRequest,
	PromoteResponse,
	PublicRoutesActionResponse,
	PublishManifestRequest,
	PublishRequest,
	PublishResponse,
	PutPolicyRequest,
	RouteKeyResponse,
	ScheduleActionResponse,
	SecretListResponse,
	SetConfigRequest,
	SetSecretRequest,
	StatusResponse,
	SubscriptionActionResponse,
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
	CheckManifestRequest,
	CheckManifestResponse,
	ConfigResponse,
	ConflictResponse,
	DomainResponse,
	FunctionPageResponse,
	FunctionResponse,
	FunctionRouteResponse,
	Manifest,
	ManifestErrorResponse,
	PolicyListResponse,
	PolicyResponse,
	PolicySignerRequest,
	PoolActionResponse,
	PoolSummaryResponse,
	PromotePlanResponse,
	PromoteResponse,
	PublicRoutesActionResponse,
	PublishManifestRequest,
	PublishResponse,
	RouteKeyResponse,
	ScheduleActionResponse,
	SecretListResponse,
	StatusResponse,
	SubscriptionActionResponse,
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

	/**
	 * `POST …/manifest/check` (M2.2): validates a manifest and, when valid,
	 * returns the promote plan — writes nothing (no version reserved, no row
	 * locked). Same permission and reach as `publishVersion`.
	 */
	checkManifest(
		address: string,
		data: CheckManifestRequest,
	): Promise<CheckManifestResponse> {
		return apiFetch(`/functions/${encodeURIComponent(address)}/manifest/check`, {
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

	/**
	 * Convenience wrapper over `promoteAlias` for the Versions tab's promote
	 * drawer: `alias` defaults to `live` (spec `function-zones-and-aliases.md`
	 * §6, matching `fn promote --alias`'s own default) — any other name is a
	 * NAMED alias, HTTP-only (no wiring change).
	 */
	promote(
		address: string,
		version: number,
		alias: string = "live",
	): Promise<PromoteResponse> {
		return functionsApi.promoteAlias(address, alias, { version });
	},

	listAliases(address: string): Promise<AliasResponse[]> {
		return apiFetch(`/functions/${encodeURIComponent(address)}/aliases`);
	},

	/** 204 No Content on the wire — reload the alias list from `listAliases` after. */
	deleteAlias(address: string, alias: string): Promise<void> {
		return apiFetch(
			`/functions/${encodeURIComponent(address)}/aliases/${encodeURIComponent(alias)}`,
			{ method: "DELETE" },
		);
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

	getConfig(address: string, version?: number): Promise<ConfigResponse> {
		const query = version !== undefined ? `?version=${version}` : "";
		return apiFetch(
			`/functions/${encodeURIComponent(address)}/config${query}`,
		);
	},

	setConfig(
		address: string,
		data: SetConfigRequest,
		version?: number,
	): Promise<ConfigResponse> {
		const query = version !== undefined ? `?version=${version}` : "";
		return apiFetch(
			`/functions/${encodeURIComponent(address)}/config${query}`,
			{
				method: "PUT",
				body: JSON.stringify(data),
			},
		);
	},

	/**
	 * `options` is an additive pass-through (unused by any existing caller)
	 * so the secrets tab can request `suppressGlobalErrorToast` for the
	 * ENCRYPTION_UNCONFIGURED 503 (docs/spec/function-ui.md §2.1: "renders as
	 * a disabled state with the reason, not as an error toast").
	 */
	listSecrets(
		address: string,
		version?: number,
		options?: FetchOptions,
	): Promise<SecretListResponse> {
		const query = version !== undefined ? `?version=${version}` : "";
		return apiFetch(
			`/functions/${encodeURIComponent(address)}/secrets${query}`,
			options,
		);
	},

	/** 204 No Content — the value is never returned, only "set"/"not set". */
	setSecret(
		address: string,
		key: string,
		data: SetSecretRequest,
		options?: FetchOptions,
	): Promise<void> {
		return apiFetch(
			`/functions/${encodeURIComponent(address)}/secrets/${encodeURIComponent(key)}`,
			{ method: "PUT", body: JSON.stringify(data), ...options },
		);
	},

	deleteSecret(
		address: string,
		key: string,
		options?: FetchOptions,
	): Promise<void> {
		return apiFetch(
			`/functions/${encodeURIComponent(address)}/secrets/${encodeURIComponent(key)}`,
			{ method: "DELETE", ...options },
		);
	},

	listPolicies(): Promise<PolicyListResponse> {
		return apiFetch("/function-policies");
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

	getDomain(hostname: string): Promise<DomainResponse> {
		return apiFetch(`/function-domains/${encodeURIComponent(hostname)}`);
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
