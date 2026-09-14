/**
 * Cursor pagination composable.
 *
 * Backs list pages over endpoints that return `{ items, hasMore, nextCursor }`
 * instead of `{ items, total, page, size }`. The high-volume endpoints
 * (events, dispatch jobs, audit logs, login attempts) can sit on billions of
 * rows where `SELECT COUNT(*)` is a non-starter; backend keysets on
 * `(created_at DESC, id DESC)` and the page is fetched without counting.
 *
 * UX shape: "first page" + "Older →" forward navigation, plus an in-session
 * cursor stack so the user can step back without a server-side reverse query.
 * After a refresh the stack resets to the first page — that's the standard
 * cursor-paginated tradeoff.
 *
 * Filter changes (or `reset()`) clear the stack so the next fetch lands back
 * on page 1.
 */

import { ref, type Ref } from "vue";

export interface CursorPage<T> {
	items: T[];
	hasMore: boolean;
	nextCursor?: string | undefined;
}

export interface UseCursorPaginationOptions<T> {
	/**
	 * Fetcher. Receives the cursor for the page being requested
	 * (`undefined` for first page) and returns the page payload.
	 */
	fetchPage: (cursor: string | undefined) => Promise<CursorPage<T>>;
	/** Optional: called when an unhandled fetch rejection occurs. */
	onError?: (err: unknown) => void;
}

export interface UseCursorPagination<T> {
	items: Ref<T[]>;
	loading: Ref<boolean>;
	hasMore: Ref<boolean>;
	hasPrev: Ref<boolean>;
	page: Ref<number>;
	/** First page (no cursor). Resets the stack. */
	loadFirst: () => Promise<void>;
	/** Forward → next page using current `nextCursor`. */
	loadNext: () => Promise<void>;
	/** Step back to the previously rendered page (in-session). */
	loadPrev: () => Promise<void>;
	/** Re-fetch the current page (e.g. after a mutation). */
	refresh: () => Promise<void>;
	/** Same as loadFirst. Renamed for filter-change call sites. */
	reset: () => Promise<void>;
}

export function useCursorPagination<T>(
	options: UseCursorPaginationOptions<T>,
): UseCursorPagination<T> {
	const items = ref([]) as Ref<T[]>;
	const loading = ref(false);
	const hasMore = ref(false);
	// Stack of cursors used to fetch each rendered page so far.
	// `[undefined]` = page 1; `[undefined, "ABC..."]` = page 2 was fetched
	// using cursor "ABC..."; etc. The TOP of the stack is the cursor that
	// produced the *currently displayed* page.
	const cursorStack = ref<(string | undefined)[]>([undefined]);
	// `nextCursor` from the latest server response — the cursor we'd send if
	// the user clicks "Older". `undefined` means no more pages.
	const nextCursor = ref<string | undefined>(undefined);

	const page = ref(1);
	const hasPrev = ref(false);

	async function fetchAt(cursor: string | undefined): Promise<void> {
		loading.value = true;
		try {
			const result = await options.fetchPage(cursor);
			items.value = result.items;
			hasMore.value = result.hasMore;
			nextCursor.value = result.nextCursor;
		} catch (err) {
			options.onError?.(err);
			throw err;
		} finally {
			loading.value = false;
		}
	}

	async function loadFirst() {
		cursorStack.value = [undefined];
		page.value = 1;
		hasPrev.value = false;
		await fetchAt(undefined);
	}

	async function loadNext() {
		if (!hasMore.value || nextCursor.value === undefined) return;
		const next = nextCursor.value;
		await fetchAt(next);
		cursorStack.value.push(next);
		page.value += 1;
		hasPrev.value = cursorStack.value.length > 1;
	}

	async function loadPrev() {
		if (cursorStack.value.length <= 1) return;
		cursorStack.value.pop();
		page.value -= 1;
		hasPrev.value = cursorStack.value.length > 1;
		const top = cursorStack.value[cursorStack.value.length - 1];
		await fetchAt(top);
	}

	async function refresh() {
		const top = cursorStack.value[cursorStack.value.length - 1];
		await fetchAt(top);
	}

	return {
		items,
		loading,
		hasMore,
		hasPrev,
		page,
		loadFirst,
		loadNext,
		loadPrev,
		refresh,
		reset: loadFirst,
	};
}
