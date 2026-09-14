/**
 * SDK Error Types
 *
 * Discriminated union types for typed error handling with neverthrow.
 * All errors have a `type` field for pattern matching.
 */

/** Authentication errors */
export type AuthenticationError =
	| { type: "missing_credentials"; message: string }
	| { type: "invalid_credentials"; message: string }
	| { type: "token_expired"; message: string }
	| { type: "token_fetch_failed"; message: string; cause?: Error };

/** HTTP/Network errors */
export type HttpError =
	| { type: "network"; message: string; cause?: Error }
	| { type: "timeout"; message: string; durationMs: number }
	| { type: "http_error"; status: number; message: string; body?: unknown };

/** Validation errors (422) */
export type ValidationError = {
	type: "validation";
	message: string;
	errors: Record<string, string[]>;
};

/** Not found errors (404) */
export type NotFoundError = {
	type: "not_found";
	message: string;
	resource?: string;
	id?: string;
};

/** Forbidden errors (403) */
export type ForbiddenError = {
	type: "forbidden";
	message: string;
};

/** Conflict errors (409) */
export type ConflictError = {
	type: "conflict";
	message: string;
	code?: string;
};

/** Rate limit errors (429) */
export type RateLimitError = {
	type: "rate_limited";
	message: string;
	retryAfterMs?: number;
};

/** Union of all SDK errors */
export type SdkError =
	| AuthenticationError
	| HttpError
	| ValidationError
	| NotFoundError
	| ForbiddenError
	| ConflictError
	| RateLimitError;

/**
 * Create an authentication error
 */
export const authError = {
	missingCredentials: (
		message = "Client ID and secret are required",
	): AuthenticationError => ({
		type: "missing_credentials",
		message,
	}),
	invalidCredentials: (
		message = "Invalid client credentials",
	): AuthenticationError => ({
		type: "invalid_credentials",
		message,
	}),
	tokenExpired: (
		message = "Access token has expired",
	): AuthenticationError => ({
		type: "token_expired",
		message,
	}),
	tokenFetchFailed: (message: string, cause?: Error): AuthenticationError => ({
		type: "token_fetch_failed",
		message,
		cause,
	}),
};

/**
 * Create an HTTP error
 */
export const httpError = {
	network: (message: string, cause?: Error): HttpError => ({
		type: "network",
		message,
		cause,
	}),
	timeout: (durationMs: number): HttpError => ({
		type: "timeout",
		message: `Request timed out after ${durationMs}ms`,
		durationMs,
	}),
	http: (status: number, message: string, body?: unknown): HttpError => ({
		type: "http_error",
		status,
		message,
		body,
	}),
};

/**
 * Create a validation error from API response
 */
export const validationError = (
	message: string,
	errors: Record<string, string[]> = {},
): ValidationError => ({
	type: "validation",
	message,
	errors,
});

/**
 * Create a not found error
 */
export const notFoundError = (
	message: string,
	resource?: string,
	id?: string,
): NotFoundError => ({
	type: "not_found",
	message,
	resource,
	id,
});

/**
 * Create a forbidden error
 */
export const forbiddenError = (
	message = "Access forbidden",
): ForbiddenError => ({
	type: "forbidden",
	message,
});

/**
 * Create a conflict error
 */
export const conflictError = (
	message: string,
	code?: string,
): ConflictError => ({
	type: "conflict",
	message,
	code,
});

/**
 * Create a rate limit error
 */
export const rateLimitError = (
	message: string,
	retryAfterMs?: number,
): RateLimitError => ({
	type: "rate_limited",
	message,
	retryAfterMs,
});

/**
 * Map HTTP status code to appropriate error
 */
export function mapHttpStatusToError(
	status: number,
	body?: unknown,
	message?: string,
): SdkError {
	const errorBody = body as Record<string, unknown> | undefined;
	// Platform JSON: { error: "<CODE>", message: "<human text>" }
	// Prefer the human message; fall back to the code, then a generic.
	const errorMessage =
		message ??
		errorBody?.["message"]?.toString() ??
		errorBody?.["error"]?.toString() ??
		`HTTP ${status}`;
	const errorCode = errorBody?.["error"]?.toString();

	switch (status) {
		case 401:
			return authError.tokenExpired(errorMessage);
		case 403:
			return forbiddenError(errorMessage);
		case 404:
			return notFoundError(errorMessage);
		case 409:
			return conflictError(errorMessage, errorCode);
		case 422:
			return validationError(
				errorMessage,
				(errorBody?.["errors"] as Record<string, string[]>) ?? {},
			);
		case 429: {
			const retryAfter = errorBody?.["retryAfter"] as number | undefined;
			return rateLimitError(
				errorMessage,
				retryAfter ? retryAfter * 1000 : undefined,
			);
		}
		default:
			return httpError.http(status, errorMessage, body);
	}
}
