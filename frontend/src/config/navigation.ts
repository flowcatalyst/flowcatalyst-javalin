export interface NavItem {
	label: string;
	icon: string;
	route?: string;
	children?: NavItem[];
	expanded?: boolean;
	// Restrict an item to a single audience by scope. "anchor" shows only to
	// anchor-scoped users (platform admins); "client" shows only to client/
	// partner-scoped users (client-administrators). Used to split the platform
	// user-management page from the client-scoped one. Omit for items everyone
	// who holds the route permission may see.
	scope?: "anchor" | "client";
}

export interface NavGroup {
	label: string;
	items: NavItem[];
}

/** The minimal user shape the filtering below needs. */
export interface NavUser {
	permissions?: string[];
	roles?: string[];
	clientId?: string | null;
}

export const NAVIGATION_CONFIG: NavGroup[] = [
	{
		label: "Overview",
		items: [
			{
				label: "Dashboard",
				icon: "pi pi-home",
				route: "/dashboard",
				// Platform-wide stats — anchor (platform-admin) audience only.
				scope: "anchor",
			},
		],
	},
	{
		label: "Identity & Access",
		items: [
			{
				label: "User Management",
				icon: "pi pi-users",
				route: "/users",
				scope: "anchor",
			},
			{
				label: "Service Accounts",
				icon: "pi pi-server",
				route: "/identity/service-accounts",
			},
			{
				label: "Developer Users",
				icon: "pi pi-code",
				route: "/identity/developer-users",
				scope: "anchor",
			},
			{
				label: "Identity Providers",
				icon: "pi pi-id-card",
				route: "/authentication/identity-providers",
			},
			{
				label: "Email Domains",
				icon: "pi pi-envelope",
				route: "/authentication/email-domain-mappings",
			},
			{
				label: "OAuth Clients",
				icon: "pi pi-key",
				route: "/authentication/oauth-clients",
			},
			{
				label: "Roles",
				icon: "pi pi-shield",
				route: "/authorization/roles",
				// Role-definition management is a platform concern; client
				// administrators assign roles to users from the client-scoped
				// user pages and never manage the role catalogue itself.
				scope: "anchor",
			},
			{
				label: "Permissions",
				icon: "pi pi-lock",
				route: "/authorization/permissions",
				scope: "anchor",
			},
		],
	},
	{
		label: "Client Administration",
		items: [
			{
				label: "User Management",
				icon: "pi pi-users",
				route: "/client-administration/users",
				scope: "client",
			},
			{
				label: "Reset Approvals",
				icon: "pi pi-shield",
				route: "/authentication/reset-approvals",
			},
		],
	},
	{
		// The portal plane: the portals each client runs for its customers,
		// and those portals' end users (separate from platform users).
		label: "Portal",
		items: [
			{
				label: "Portal Apps",
				icon: "pi pi-window-maximize",
				route: "/identity/portal-apps",
			},
			{
				label: "Portal Users",
				icon: "pi pi-globe",
				route: "/identity/portal-users",
			},
		],
	},
	{
		label: "Platform",
		items: [
			{
				label: "Applications",
				icon: "pi pi-th-large",
				route: "/applications",
			},
			{
				label: "Clients",
				icon: "pi pi-building",
				route: "/clients",
			},
			{
				label: "CORS Origins",
				icon: "pi pi-link",
				route: "/platform/cors",
			},
			{
				label: "Audit Log",
				icon: "pi pi-history",
				route: "/platform/audit-log",
			},
			{
				label: "Documentation",
				icon: "pi pi-book",
				route: "/platform/docs",
				// Platform reference docs — administrators to start.
				scope: "anchor",
			},
			{
				label: "Login Attempts",
				icon: "pi pi-sign-in",
				route: "/platform/login-attempts",
			},
			{
				label: "Settings",
				icon: "pi pi-cog",
				expanded: false,
				children: [
					{
						label: "Theme",
						icon: "pi pi-palette",
						route: "/platform/settings/theme",
					},
					{
						label: "Names",
						icon: "pi pi-tag",
						route: "/platform/settings/names",
					},
				],
			},
			{
				label: "Debug",
				icon: "pi pi-wrench",
				expanded: false,
				children: [
					{
						label: "Raw Events",
						icon: "pi pi-database",
						route: "/platform/debug/events",
					},
					{
						label: "Raw Dispatch Jobs",
						icon: "pi pi-database",
						route: "/platform/debug/dispatch-jobs",
					},
				],
			},
		],
	},
	{
		label: "Messaging",
		items: [
			{
				label: "Events",
				icon: "pi pi-inbox",
				route: "/events",
			},
			{
				label: "Event Types",
				icon: "pi pi-bolt",
				route: "/event-types",
			},
			{
				label: "Subscriptions",
				icon: "pi pi-bell",
				route: "/subscriptions",
			},
			{
				label: "Connections",
				icon: "pi pi-link",
				route: "/connections",
			},
			{
				label: "Dispatch Pools",
				icon: "pi pi-database",
				route: "/dispatch-pools",
			},
			{
				label: "Dispatch Jobs",
				icon: "pi pi-send",
				route: "/dispatch-jobs",
			},
			{
				label: "Scheduled Jobs",
				icon: "pi pi-clock",
				route: "/scheduled-jobs",
			},
		],
	},
	{
		// The function service (docs/function-service-overview.md): functions,
		// the domains their public routes are served on, and the per-owner
		// signing/limit policy. Positioned after "Dispatch Jobs" (Messaging).
		label: "Functions",
		items: [
			{
				label: "Functions",
				icon: "pi pi-bolt",
				route: "/functions",
			},
			{
				label: "Domains",
				icon: "pi pi-globe",
				route: "/function-domains",
			},
			{
				label: "Policies",
				icon: "pi pi-verified",
				route: "/function-policies",
			},
		],
	},
	{
		label: "Developer",
		items: [
			{
				label: "Applications",
				icon: "pi pi-book",
				route: "/developer",
			},
			{
				label: "Processes",
				icon: "pi pi-sitemap",
				route: "/processes",
			},
		],
	},
];

/**
 * Reduce a single item to what this user may see: dropped by scope, dropped
 * by missing route permission (children filtered, parent dropped once empty).
 * Extracted as a pure function (from AppSidebar.vue's inline `visibleItem`)
 * so the filtering behaviour is unit-testable without mounting the sidebar.
 */
function visibleNavItem(
	item: NavItem,
	user: NavUser | null | undefined,
	canSeeScope: (user: NavUser | null | undefined, scope: NavItem["scope"]) => boolean,
	canAccessPath: (user: NavUser | null | undefined, path: string) => boolean,
): NavItem | null {
	if (!canSeeScope(user, item.scope)) return null;
	if (item.children && item.children.length > 0) {
		const children = item.children.filter(
			(c) => canSeeScope(user, c.scope) && (!c.route || canAccessPath(user, c.route)),
		);
		return children.length > 0 ? { ...item, children } : null;
	}
	if (item.route && !canAccessPath(user, item.route)) return null;
	return item;
}

/**
 * Pure navigation filter: the groups and items a given user may see, given
 * the platform's scope/permission predicates and whether messaging is
 * enabled. The single source both AppSidebar.vue and its tests use, so a
 * change to the filtering rule can't drift between what renders and what's
 * tested.
 */
export function filterNavigation(
	groups: NavGroup[],
	user: NavUser | null | undefined,
	options: {
		messagingEnabled: boolean;
		canSeeScope: (user: NavUser | null | undefined, scope: NavItem["scope"]) => boolean;
		canAccessPath: (user: NavUser | null | undefined, path: string) => boolean;
	},
): NavGroup[] {
	return groups
		.filter((group) => group.label !== "Messaging" || options.messagingEnabled)
		.map((group) => ({
			...group,
			items: group.items
				.map((item) => visibleNavItem(item, user, options.canSeeScope, options.canAccessPath))
				.filter((item): item is NavItem => item !== null),
		}))
		.filter((group) => group.items.length > 0);
}
