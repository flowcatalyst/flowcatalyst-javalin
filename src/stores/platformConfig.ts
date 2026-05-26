import { defineStore } from "pinia";
import { ref, computed } from "vue";

export interface PlatformFeatures {
	messagingEnabled: boolean;
}

export interface PlatformConfig {
	features: PlatformFeatures;
}

const DEFAULT_CONFIG: PlatformConfig = {
	features: {
		messagingEnabled: true,
	},
};

export const usePlatformConfigStore = defineStore("platformConfig", () => {
	// State
	const config = ref<PlatformConfig>(DEFAULT_CONFIG);
	const isLoaded = ref(false);
	const error = ref<string | null>(null);

	// Computed
	const messagingEnabled = computed(
		() => config.value.features.messagingEnabled,
	);

	// Actions
	async function loadConfig(): Promise<void> {
		if (isLoaded.value) return;

		try {
			const response = await fetch("/api/config/platform");
			if (response.ok) {
				const data = await response.json();
				config.value = data;
			} else {
				console.warn("Failed to load platform config, using defaults");
			}
		} catch (err) {
			console.warn("Failed to load platform config, using defaults:", err);
			error.value = err instanceof Error ? err.message : "Unknown error";
		} finally {
			isLoaded.value = true;
		}
	}

	function reset() {
		config.value = DEFAULT_CONFIG;
		isLoaded.value = false;
		error.value = null;
	}

	return {
		// State
		config,
		isLoaded,
		error,
		// Computed
		messagingEnabled,
		// Actions
		loadConfig,
		reset,
	};
});
