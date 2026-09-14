import { useRoute, useRouter } from "vue-router";

/**
 * Preserves list-page filter context across list → detail → back navigation.
 *
 * List pages call `navigateToDetail('/clients/abc')` instead of `router.push`,
 * which appends `?from=<current-fullPath>`. Detail pages call
 * `returnTo('/clients')` for their back button, which reads `from` from the
 * URL and returns the user to the exact filtered view they came from.
 *
 * Falls back to the provided list path if `from` is missing or not a
 * safe same-origin path (guards against `?from=https://evil.com` injection).
 */
export function useReturnTo() {
	const router = useRouter();
	const route = useRoute();

	function navigateToDetail(
		path: string,
		extraQuery?: Record<string, string>,
	) {
		router.push({
			path,
			query: { ...(extraQuery ?? {}), from: route.fullPath },
		});
	}

	function returnTo(fallback: string) {
		const from = route.query["from"];
		if (typeof from === "string" && isSafeInternalPath(from)) {
			router.push(from);
		} else {
			router.push(fallback);
		}
	}

	/**
	 * Navigate to `path` while propagating the current `?from=` through.
	 * Use this for intra-flow hops (e.g. detail → child-page → back to detail)
	 * so the original list context survives the whole chain.
	 */
	function forwardFrom(path: string) {
		const from = route.query["from"];
		if (typeof from === "string" && isSafeInternalPath(from)) {
			router.push({ path, query: { from } });
		} else {
			router.push(path);
		}
	}

	return { navigateToDetail, returnTo, forwardFrom };
}

/** Only accept same-origin paths: must start with a single `/` (not `//`). */
function isSafeInternalPath(path: string): boolean {
	return path.startsWith("/") && !path.startsWith("//");
}
