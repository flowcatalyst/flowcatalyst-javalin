import { defineStore } from "pinia";
import { ref, computed } from "vue";

export interface LoginTheme {
	brandName: string;
	brandSubtitle: string;
	logoUrl?: string;
	logoSvg?: string;
	logoHeight?: number;
	primaryColor: string;
	accentColor: string;
	backgroundColor: string;
	backgroundGradient?: string;
	footerText: string;
	customCss?: string;
}

const DEFAULT_THEME: LoginTheme = {
	brandName: "FlowCatalyst",
	brandSubtitle: "Platform Administration",
	logoHeight: 40,
	primaryColor: "#102a43",
	accentColor: "#0967d2",
	backgroundColor: "#0a1929",
	backgroundGradient: "linear-gradient(135deg, #102a43 0%, #0a1929 100%)",
	footerText: "Secure access to your FlowCatalyst platform",
};

export const useLoginThemeStore = defineStore("loginTheme", () => {
	// State
	const theme = ref<LoginTheme>(DEFAULT_THEME);
	const isLoaded = ref(false);
	const error = ref<string | null>(null);

	// Computed
	const hasCustomLogo = computed(() =>
		Boolean(theme.value.logoUrl || theme.value.logoSvg),
	);

	const background = computed(
		() => theme.value.backgroundGradient || theme.value.backgroundColor,
	);

	// Actions
	async function loadTheme(clientId?: string): Promise<void> {
		if (isLoaded.value) return;

		try {
			const url = clientId
				? `/api/public/login-theme?clientId=${encodeURIComponent(clientId)}`
				: "/api/public/login-theme";

			const response = await fetch(url);
			if (response.ok) {
				const data = await response.json();
				theme.value = { ...DEFAULT_THEME, ...data };
			} else {
				console.warn("Failed to load login theme, using defaults");
			}
		} catch (err) {
			console.warn("Failed to load login theme, using defaults:", err);
			error.value = err instanceof Error ? err.message : "Unknown error";
		} finally {
			isLoaded.value = true;
		}
	}

	function applyThemeColors(): void {
		const root = document.documentElement;
		root.style.setProperty("--login-primary", theme.value.primaryColor);
		root.style.setProperty("--login-accent", theme.value.accentColor);
		root.style.setProperty("--login-bg", theme.value.backgroundColor);

		// Apply custom CSS if provided
		if (theme.value.customCss) {
			let styleEl = document.getElementById("login-custom-css");
			if (!styleEl) {
				styleEl = document.createElement("style");
				styleEl.id = "login-custom-css";
				document.head.appendChild(styleEl);
			}
			styleEl.textContent = theme.value.customCss;
		}
	}

	function reset(): void {
		theme.value = DEFAULT_THEME;
		isLoaded.value = false;
		error.value = null;

		// Remove custom CSS
		const styleEl = document.getElementById("login-custom-css");
		if (styleEl) {
			styleEl.remove();
		}
	}

	return {
		// State
		theme,
		isLoaded,
		error,
		// Computed
		hasCustomLogo,
		background,
		// Actions
		loadTheme,
		applyThemeColors,
		reset,
	};
});
