import { apiFetch } from "./client";

export interface LoginTheme {
	brandName: string;
	brandSubtitle: string;
	logoUrl?: string | null;
	logoSvg?: string | null;
	logoHeight?: number;
	primaryColor: string;
	accentColor: string;
	backgroundColor: string;
	backgroundGradient?: string | null;
	footerText: string;
	customCss?: string | null;
}

export interface PlatformConfig {
	id: string;
	applicationCode: string;
	section: string;
	property: string;
	scope: string;
	clientId: string | null;
	valueType: string;
	value: string;
	description: string | null;
	createdAt: string;
	updatedAt: string;
}

export interface SetConfigRequest {
	value: string;
	valueType?: "PLAIN" | "SECRET";
	description?: string;
}

export const configApi = {
	// Get login theme (public, no auth needed)
	getLoginTheme(clientId?: string): Promise<LoginTheme> {
		const url = clientId
			? `/public/login-theme?clientId=${encodeURIComponent(clientId)}`
			: "/public/login-theme";
		return apiFetch(url);
	},

	// Get a config value
	getConfig(
		appCode: string,
		section: string,
		property: string,
		scope = "GLOBAL",
		clientId?: string,
	): Promise<PlatformConfig> {
		let url = `/config/${appCode}/${section}/${property}?scope=${scope}`;
		if (clientId) url += `&clientId=${encodeURIComponent(clientId)}`;
		return apiFetch(url);
	},

	// Set a config value
	setConfig(
		appCode: string,
		section: string,
		property: string,
		data: SetConfigRequest,
		scope = "GLOBAL",
		clientId?: string,
	): Promise<PlatformConfig> {
		let url = `/config/${appCode}/${section}/${property}?scope=${scope}`;
		if (clientId) url += `&clientId=${encodeURIComponent(clientId)}`;
		return apiFetch(url, {
			method: "PUT",
			body: JSON.stringify(data),
		});
	},

	// Delete a config
	deleteConfig(
		appCode: string,
		section: string,
		property: string,
		scope = "GLOBAL",
		clientId?: string,
	): Promise<void> {
		let url = `/config/${appCode}/${section}/${property}?scope=${scope}`;
		if (clientId) url += `&clientId=${encodeURIComponent(clientId)}`;
		return apiFetch(url, { method: "DELETE" });
	},

	// Helper specifically for login theme
	getLoginThemeConfig(): Promise<string | null> {
		return apiFetch<PlatformConfig>(
			"/config/platform/login/theme?scope=GLOBAL",
		)
			.then((response: PlatformConfig) => response.value)
			.catch(() => null);
	},

	setLoginThemeConfig(theme: LoginTheme): Promise<PlatformConfig> {
		return apiFetch("/config/platform/login/theme?scope=GLOBAL", {
			method: "PUT",
			body: JSON.stringify({
				value: JSON.stringify(theme),
				valueType: "PLAIN",
				description: "Login page theme configuration",
			}),
		});
	},

	// Platform name — the brand shown in emails, the authenticator app (2FA
	// issuer), passkey prompts, and the SPA. Stored at platform/branding/platform-name.
	getPlatformName(): Promise<string | null> {
		return apiFetch<PlatformConfig>(
			"/config/platform/branding/platform-name?scope=GLOBAL",
		)
			.then((response: PlatformConfig) => response.value)
			.catch(() => null);
	},

	setPlatformName(name: string): Promise<PlatformConfig> {
		return apiFetch("/config/platform/branding/platform-name?scope=GLOBAL", {
			method: "PUT",
			body: JSON.stringify({
				value: name,
				valueType: "PLAIN",
				description: "Platform display name",
			}),
		});
	},
};
