import { ref } from "vue";
import { filterOptionsApi, type FilterOption } from "@/api/filter-options";

// Module-level singleton state: fetched once per session and shared across
// every caller (dropdown + label-lookup sites).
const options = ref<FilterOption[]>([]);
const loading = ref(false);
const loaded = ref(false);
let inflight: Promise<void> | null = null;

async function ensureLoaded(): Promise<void> {
	if (loaded.value) return;
	if (inflight) return inflight;

	loading.value = true;
	inflight = (async () => {
		try {
			const response = await filterOptionsApi.clients();
			options.value = response.clients;
			loaded.value = true;
		} finally {
			loading.value = false;
			inflight = null;
		}
	})();
	return inflight;
}

function getLabel(id: string | null | undefined): string {
	if (!id) return "";
	return options.value.find((o) => o.value === id)?.label ?? id;
}

/** Invalidate the cache — e.g. after a client is created/renamed elsewhere. */
function reload(): Promise<void> {
	loaded.value = false;
	inflight = null;
	return ensureLoaded();
}

export function useClientOptions() {
	return { options, loading, ensureLoaded, getLabel, reload };
}
