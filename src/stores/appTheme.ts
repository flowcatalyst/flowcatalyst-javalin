import { defineStore } from "pinia";
import { ref, computed } from "vue";
import type { LoginTheme } from "@/api/config";

const DEFAULT_LOGO_HEIGHT = 40;

const DEFAULT_THEME: LoginTheme = {
	brandName: "FlowCatalyst",
	brandSubtitle: "Platform Administration",
	logoHeight: DEFAULT_LOGO_HEIGHT,
	primaryColor: "#102a43",
	accentColor: "#0967d2",
	backgroundColor: "#0a1929",
	backgroundGradient: "linear-gradient(135deg, #102a43 0%, #0a1929 100%)",
	footerText: "Secure access to your FlowCatalyst platform",
};

export const useAppThemeStore = defineStore("appTheme", () => {
	// State
	const theme = ref<LoginTheme>(DEFAULT_THEME);
	const isLoaded = ref(false);

	// Computed
	const hasCustomLogo = computed(() =>
		Boolean(theme.value.logoUrl || theme.value.logoSvg),
	);

	const brandName = computed(() => theme.value.brandName);
	const logoUrl = computed(() => theme.value.logoUrl);
	const logoSvg = computed(() => theme.value.logoSvg);
	const logoHeight = computed(
		() => theme.value.logoHeight ?? DEFAULT_LOGO_HEIGHT,
	);

	// Actions
	async function loadTheme(): Promise<void> {
		if (isLoaded.value) return;

		try {
			const response = await fetch("/api/public/login-theme");
			if (response.ok) {
				const data = await response.json();
				theme.value = { ...DEFAULT_THEME, ...data };
			}
		} catch (err) {
			console.warn("Failed to load app theme, using defaults:", err);
		} finally {
			isLoaded.value = true;
		}
	}

	return {
		// State
		theme,
		isLoaded,
		// Computed
		hasCustomLogo,
		brandName,
		logoUrl,
		logoSvg,
		logoHeight,
		// Actions
		loadTheme,
	};
});
