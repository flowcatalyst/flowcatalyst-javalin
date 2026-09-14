import { ref, watch, computed, type Ref } from "vue";
import { useRouter, useRoute } from "vue-router";

// --- Filter field definitions ---

interface StringField {
	type: "string";
	key: string;
	default?: string;
}

interface BooleanField {
	type: "boolean";
	key: string;
	default?: boolean;
}

interface ArrayField {
	type: "array";
	key: string;
	default?: string[];
}

interface NumberField {
	type: "number";
	key: string;
	default?: number;
}

type FilterField = StringField | BooleanField | ArrayField | NumberField;

interface ListStateOptions<F extends Record<string, FilterField>> {
	/** Filter field definitions. Keys become the ref names; `key` is the URL query param name. */
	filters: F;
	/** Default page size */
	pageSize?: number;
	/** Default sort field */
	sortField?: string;
	/** Default sort order */
	sortOrder?: "asc" | "desc";
	/** Debounce delay (ms) for string fields — typically for search. 0 = no debounce. */
	debounce?: number;
	/** Fields to debounce (by filter key). Defaults to fields named "q" or "search". */
	debounceFields?: string[];
}

// --- Inferred filter refs type ---

type FilterRefs<F extends Record<string, FilterField>> = {
	[K in keyof F]: F[K]["type"] extends "string"
		? Ref<string>
		: F[K]["type"] extends "boolean"
			? Ref<boolean | null>
			: F[K]["type"] extends "array"
				? Ref<string[]>
				: F[K]["type"] extends "number"
					? Ref<number | null>
					: Ref<unknown>;
};

// --- URL parsing helpers ---

function parseQueryString(value: unknown): string {
	if (typeof value === "string" && value.length > 0) return value;
	return "";
}

function parseQueryBoolean(value: unknown): boolean | null {
	if (value === "true") return true;
	if (value === "false") return false;
	return null;
}

function parseQueryArray(value: unknown): string[] {
	if (!value) return [];
	if (Array.isArray(value))
		return value.filter((v): v is string => typeof v === "string");
	if (typeof value === "string") return value.split(",").filter(Boolean);
	return [];
}

function parseQueryNumber(value: unknown): number | null {
	if (typeof value === "string" && value.length > 0) {
		const n = Number(value);
		if (!Number.isNaN(n)) return n;
	}
	return null;
}

/**
 * URL-synced list state composable.
 *
 * Manages filters, pagination, sorting — all backed by URL query params.
 * Replaces (not pushes) history on change, so no back-button spam.
 *
 * Usage:
 * ```ts
 * const { filters, page, pageSize, sortField, sortOrder, hasActiveFilters, clearFilters, onPage, onSort } =
 *   useListState({
 *     filters: {
 *       q:           { type: 'string',  key: 'q' },
 *       clientId:    { type: 'string',  key: 'clientId' },
 *       active:      { type: 'boolean', key: 'active' },
 *       roles:       { type: 'array',   key: 'roles' },
 *       application: { type: 'string',  key: 'app' },
 *     },
 *     pageSize: 100,
 *     sortField: 'createdAt',
 *     sortOrder: 'desc',
 *   });
 *
 * // filters.q, filters.clientId, etc. are reactive refs
 * // Watch `onStateChange` callback for when to reload data
 * ```
 */
export function useListState<F extends Record<string, FilterField>>(
	options: ListStateOptions<F>,
	onChange?: () => void,
) {
	const router = useRouter();
	const route = useRoute();

	const defaultPageSize = options.pageSize ?? 100;
	const defaultSortField = options.sortField ?? "createdAt";
	const defaultSortOrder = options.sortOrder ?? "desc";
	const debounceMs = options.debounce ?? 300;
	const debounceFields = new Set(
		options.debounceFields ?? ["q", "search"],
	);

	// --- Build filter refs from URL ---
	const filters = {} as FilterRefs<F>;
	for (const [name, field] of Object.entries(options.filters)) {
		const urlVal = route.query[field.key];
		switch (field.type) {
			case "string":
				(filters as Record<string, Ref>)[name] = ref(
					parseQueryString(urlVal) || field.default || "",
				);
				break;
			case "boolean":
				(filters as Record<string, Ref>)[name] = ref(
					parseQueryBoolean(urlVal) ?? field.default ?? null,
				);
				break;
			case "array":
				(filters as Record<string, Ref>)[name] = ref(
					parseQueryArray(urlVal).length > 0
						? parseQueryArray(urlVal)
						: field.default ?? [],
				);
				break;
			case "number":
				(filters as Record<string, Ref>)[name] = ref(
					parseQueryNumber(urlVal) ?? field.default ?? null,
				);
				break;
		}
	}

	// --- Pagination + sort from URL ---
	const page = ref(parseQueryNumber(route.query["page"]) ?? 0);
	const pageSize = ref(
		parseQueryNumber(route.query["pageSize"]) ?? defaultPageSize,
	);
	const sortField = ref(
		parseQueryString(route.query["sortField"]) || defaultSortField,
	);
	const sortOrder = ref<"asc" | "desc">(
		(parseQueryString(route.query["sortOrder"]) as "asc" | "desc") ||
			defaultSortOrder,
	);

	// --- Suppress flag to prevent watch loops ---
	let suppress = false;

	// --- Sync state → URL ---
	function syncToUrl() {
		if (suppress) return;

		const query: Record<string, string | undefined> = {};

		for (const [name, field] of Object.entries(options.filters)) {
			const val = (filters as Record<string, Ref>)[name]!.value;
			switch (field.type) {
				case "string":
					if (val && val !== (field.default || ""))
						query[field.key] = val;
					break;
				case "boolean":
					if (val !== null && val !== (field.default ?? null))
						query[field.key] = String(val);
					break;
				case "array":
					if (Array.isArray(val) && val.length > 0)
						query[field.key] = val.join(",");
					break;
				case "number":
					if (val !== null && val !== (field.default ?? null))
						query[field.key] = String(val);
					break;
			}
		}

		if (page.value > 0) query["page"] = String(page.value);
		if (pageSize.value !== defaultPageSize)
			query["pageSize"] = String(pageSize.value);
		if (sortField.value !== defaultSortField)
			query["sortField"] = sortField.value;
		if (sortOrder.value !== defaultSortOrder)
			query["sortOrder"] = sortOrder.value;

		router.replace({ query });
	}

	// --- Watch all filter refs ---
	let debounceTimer: ReturnType<typeof setTimeout> | null = null;

	for (const [name] of Object.entries(options.filters)) {
		const filterRef = (filters as Record<string, Ref>)[name]!;
		const shouldDebounce = debounceFields.has(name);

		watch(filterRef, () => {
			if (suppress) return;

			// Reset page on filter change
			page.value = 0;

			if (shouldDebounce && debounceMs > 0) {
				if (debounceTimer) clearTimeout(debounceTimer);
				debounceTimer = setTimeout(() => {
					syncToUrl();
					onChange?.();
				}, debounceMs);
			} else {
				syncToUrl();
				onChange?.();
			}
		});
	}

	// Watch sort — no page reset needed
	watch([sortField, sortOrder], () => {
		syncToUrl();
		onChange?.();
	});

	// --- hasActiveFilters ---
	const hasActiveFilters = computed(() => {
		for (const [name, field] of Object.entries(options.filters)) {
			const val = (filters as Record<string, Ref>)[name]!.value;
			switch (field.type) {
				case "string":
					if (val && val !== (field.default || "")) return true;
					break;
				case "boolean":
					if (val !== null && val !== (field.default ?? null))
						return true;
					break;
				case "array":
					if (Array.isArray(val) && val.length > 0) return true;
					break;
				case "number":
					if (val !== null && val !== (field.default ?? null))
						return true;
					break;
			}
		}
		return false;
	});

	// --- Actions ---

	function clearFilters() {
		suppress = true;
		for (const [name, field] of Object.entries(options.filters)) {
			const filterRef = (filters as Record<string, Ref>)[name]!;
			switch (field.type) {
				case "string":
					filterRef.value = field.default || "";
					break;
				case "boolean":
					filterRef.value = field.default ?? null;
					break;
				case "array":
					filterRef.value = field.default ? [...field.default] : [];
					break;
				case "number":
					filterRef.value = field.default ?? null;
					break;
			}
		}
		page.value = 0;
		suppress = false;
		syncToUrl();
		onChange?.();
	}

	/** PrimeVue DataTable @page handler */
	function onPage(event: { page: number; rows: number }) {
		page.value = event.page;
		pageSize.value = event.rows;
		syncToUrl();
		onChange?.();
	}

	/** PrimeVue DataTable @sort handler */
	function onSort(event: {
		sortField?: string | ((item: unknown) => string);
		sortOrder?: number | null;
	}) {
		sortField.value =
			typeof event.sortField === "string"
				? event.sortField
				: defaultSortField;
		sortOrder.value = (event.sortOrder ?? 1) === 1 ? "asc" : "desc";
		page.value = 0;
		syncToUrl();
		onChange?.();
	}

	/** Suppress URL sync while running a callback (e.g., pruning invalid filter options) */
	function withSuppressed(fn: () => void) {
		suppress = true;
		fn();
		suppress = false;
	}

	/** Get current filter values as a plain object (useful for building API params) */
	function getFilterValues(): Record<string, unknown> {
		const result: Record<string, unknown> = {};
		for (const [name] of Object.entries(options.filters)) {
			result[name] = (filters as Record<string, Ref>)[name]!.value;
		}
		return result;
	}

	return {
		filters: filters as FilterRefs<F>,
		page,
		pageSize,
		sortField,
		sortOrder,
		hasActiveFilters,
		clearFilters,
		onPage,
		onSort,
		syncToUrl,
		withSuppressed,
		getFilterValues,
	};
}
