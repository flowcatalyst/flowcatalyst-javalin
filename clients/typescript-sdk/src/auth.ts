/**
 * OIDC Token Manager
 *
 * Handles OAuth2 client credentials flow with token caching.
 */

import { ResultAsync } from "neverthrow";
import type { AuthenticationError } from "./errors.js";
import { authError } from "./errors.js";

export interface TokenManagerConfig {
	/** Base URL of the FlowCatalyst platform */
	baseUrl: string;
	/** OAuth client ID */
	clientId: string;
	/** OAuth client secret */
	clientSecret: string;
	/** Optional custom token endpoint (defaults to {baseUrl}/oauth/token) */
	tokenUrl?: string;
}

interface TokenResponse {
	access_token: string;
	token_type: string;
	expires_in: number;
}

interface CachedToken {
	token: string;
	expiresAt: number;
}

/**
 * Manages OAuth2 access tokens with automatic refresh.
 */
export class OidcTokenManager {
	private cachedToken: CachedToken | null = null;
	private refreshPromise: Promise<string> | null = null;
	private readonly config: TokenManagerConfig;

	constructor(config: TokenManagerConfig) {
		this.config = config;
	}

	/**
	 * Get a valid access token, fetching a new one if necessary.
	 */
	getAccessToken(): ResultAsync<string, AuthenticationError> {
		// Check if we have a valid cached token (with 60s buffer)
		if (this.cachedToken && this.cachedToken.expiresAt > Date.now() + 60000) {
			return ResultAsync.fromSafePromise(
				Promise.resolve(this.cachedToken.token),
			);
		}

		// Prevent concurrent token fetches
		if (this.refreshPromise) {
			return ResultAsync.fromPromise(this.refreshPromise, (e) =>
				authError.tokenFetchFailed("Token fetch failed", e as Error),
			);
		}

		return this.fetchNewToken();
	}

	/**
	 * Force refresh the access token.
	 */
	refreshToken(): ResultAsync<string, AuthenticationError> {
		this.cachedToken = null;
		return this.fetchNewToken();
	}

	/**
	 * Check if credentials are configured.
	 */
	hasCredentials(): boolean {
		return !!(this.config.clientId && this.config.clientSecret);
	}

	/**
	 * Clear the cached token.
	 */
	clearCache(): void {
		this.cachedToken = null;
		this.refreshPromise = null;
	}

	/**
	 * Fetch a new token from the OAuth server.
	 */
	private fetchNewToken(): ResultAsync<string, AuthenticationError> {
		if (!this.hasCredentials()) {
			return ResultAsync.fromSafePromise(
				Promise.reject(authError.missingCredentials()),
			).mapErr(() => authError.missingCredentials());
		}

		const tokenUrl =
			this.config.tokenUrl ??
			`${this.config.baseUrl.replace(/\/$/, "")}/oauth/token`;

		const fetchPromise = fetch(tokenUrl, {
			method: "POST",
			headers: {
				"Content-Type": "application/x-www-form-urlencoded",
				Accept: "application/json",
			},
			body: new URLSearchParams({
				grant_type: "client_credentials",
				client_id: this.config.clientId,
				client_secret: this.config.clientSecret,
			}),
		});

		this.refreshPromise = fetchPromise.then(async (response) => {
			this.refreshPromise = null;

			if (response.status === 401 || response.status === 403) {
				throw authError.invalidCredentials();
			}

			if (!response.ok) {
				const body = await response.json().catch(() => ({}));
				const errorMsg =
					(body as Record<string, unknown>)["error_description"]?.toString() ??
					(body as Record<string, unknown>)["error"]?.toString() ??
					"Token fetch failed";
				throw authError.tokenFetchFailed(errorMsg);
			}

			const data = (await response.json()) as TokenResponse;
			if (!data.access_token) {
				throw authError.tokenFetchFailed("No access token in response");
			}

			// Cache with expiry
			this.cachedToken = {
				token: data.access_token,
				expiresAt: Date.now() + data.expires_in * 1000,
			};

			return data.access_token;
		});

		return ResultAsync.fromPromise(this.refreshPromise, (e) => {
			this.refreshPromise = null;
			if ((e as AuthenticationError).type) {
				return e as AuthenticationError;
			}
			return authError.tokenFetchFailed(
				e instanceof Error ? e.message : "Unknown error",
				e instanceof Error ? e : undefined,
			);
		});
	}
}
