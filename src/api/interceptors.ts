/**
 * API client interceptors for global error handling and retry logic.
 */

import { client } from "./generated/client.gen";
import { toast } from "@/utils/errorBus";

const MAX_RETRIES = 3;
const INITIAL_DELAY_MS = 500;

/**
 * Check if an error is a network/transient error that should be retried.
 */
function isRetryableError(error: unknown): boolean {
	// Network errors (fetch failed, no connection)
	if (error instanceof TypeError && error.message.includes("fetch")) {
		return true;
	}

	// Check for specific status codes that indicate transient issues
	if (error instanceof Response) {
		const retryableCodes = [408, 429, 502, 503, 504];
		return retryableCodes.includes(error.status);
	}

	return false;
}

/**
 * Sleep for a given number of milliseconds.
 */
function sleep(ms: number): Promise<void> {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Calculate exponential backoff delay.
 */
function getBackoffDelay(attempt: number): number {
	return INITIAL_DELAY_MS * Math.pow(2, attempt);
}

/**
 * Extract a user-friendly error message from various error types.
 */
function extractErrorMessage(error: unknown): string {
	if (error instanceof Response) {
		switch (error.status) {
			case 400:
				return "Invalid request";
			case 401:
				return "Please log in to continue";
			case 403:
				return "You do not have permission to perform this action";
			case 404:
				return "The requested resource was not found";
			case 408:
				return "Request timed out";
			case 429:
				return "Too many requests. Please try again later";
			case 500:
				return "Server error. Please try again later";
			case 502:
			case 503:
			case 504:
				return "Service temporarily unavailable";
			default:
				return `Request failed (${error.status})`;
		}
	}

	if (error instanceof TypeError && error.message.includes("fetch")) {
		return "Unable to reach the server. Please check your connection.";
	}

	if (error instanceof Error) {
		return error.message;
	}

	return "An unexpected error occurred";
}

/**
 * Track retry state per request.
 * We use a WeakMap keyed by Request to track retries.
 */
const retryCount = new WeakMap<Request, number>();

/**
 * Setup all API interceptors.
 * Call this once during app initialization.
 */
export function setupApiInterceptors(): void {
	// Request interceptor - initialize retry count
	client.interceptors.request.use(async (request) => {
		if (!retryCount.has(request)) {
			retryCount.set(request, 0);
		}
		return request;
	});

	// Response interceptor - handle errors and show toasts
	client.interceptors.response.use(async (response) => {
		// Get the original request from response
		const request = (response as Response & { request?: Request }).request;

		if (!response.ok) {
			const currentRetry = request
				? (retryCount.get(request) ?? 0)
				: MAX_RETRIES;

			// Check if we should retry
			if (isRetryableError(response) && currentRetry < MAX_RETRIES) {
				const delay = getBackoffDelay(currentRetry);
				console.log(
					`Retrying request (attempt ${currentRetry + 1}/${MAX_RETRIES}) after ${delay}ms`,
				);

				if (request) {
					retryCount.set(request, currentRetry + 1);
				}

				await sleep(delay);

				// Re-fetch the request
				if (request) {
					return fetch(request);
				}
			}

			// No more retries or not retryable - show error toast
			const errorMessage = extractErrorMessage(response);

			// Don't show toast for 401 (handled by auth redirect) unless we're already on a protected page
			if (response.status !== 401) {
				toast.error("Request Failed", errorMessage);
			}
		}

		return response;
	});
}

/**
 * Create a fetch wrapper with retry logic for use with custom apiFetch/bffFetch.
 */
export async function fetchWithRetry(
	input: RequestInfo | URL,
	init?: RequestInit,
	options?: { maxRetries?: number; showErrorToast?: boolean },
): Promise<Response> {
	const maxRetries = options?.maxRetries ?? MAX_RETRIES;
	const showErrorToast = options?.showErrorToast ?? true;
	let lastError: unknown;

	for (let attempt = 0; attempt <= maxRetries; attempt++) {
		try {
			const response = await fetch(input, init);

			if (response.ok) {
				return response;
			}

			// Check if this error is retryable
			if (isRetryableError(response) && attempt < maxRetries) {
				const delay = getBackoffDelay(attempt);
				console.log(
					`Retrying request (attempt ${attempt + 1}/${maxRetries}) after ${delay}ms`,
				);
				await sleep(delay);
				continue;
			}

			// Not retryable or out of retries
			lastError = response;
			break;
		} catch (error) {
			// Network error (fetch threw)
			if (isRetryableError(error) && attempt < maxRetries) {
				const delay = getBackoffDelay(attempt);
				console.log(
					`Retrying request after network error (attempt ${attempt + 1}/${maxRetries}) after ${delay}ms`,
				);
				await sleep(delay);
				continue;
			}

			lastError = error;
			break;
		}
	}

	// All retries exhausted
	if (showErrorToast) {
		const errorMessage = extractErrorMessage(lastError);
		toast.error("Request Failed", errorMessage);
	}

	// Throw or return error response
	if (lastError instanceof Response) {
		return lastError;
	}

	throw lastError;
}
