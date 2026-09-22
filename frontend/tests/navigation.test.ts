/**
 * U2 (docs/spec/function-ui.md §6): the sidebar hides the "Functions" nav
 * group entirely for a user holding none of its three permissions, and
 * shows exactly the entries a partial-permission user's permissions admit.
 *
 * filterNavigation() is the pure function AppSidebar.vue's filteredNavigation
 * computed delegates to (extracted from its formerly-inline `visibleItem`),
 * so this exercises the real filtering rule without mounting the sidebar.
 */

import { describe, expect, it } from "vitest";
import {
	NAVIGATION_CONFIG,
	filterNavigation,
	type NavGroup,
} from "@/config/navigation";
import { canAccessPath, canSeeScope } from "@/stores/permissions";

const noneOfTheThree = {
	clientId: null,
	roles: ["some-role"],
	permissions: ["platform:messaging:event:view"],
};
const onlyFunctionView = {
	clientId: null,
	roles: ["some-role"],
	permissions: ["platform:function:function:view"],
};
const allThree = {
	clientId: null,
	roles: ["some-role"],
	permissions: [
		"platform:function:function:view",
		"platform:function:domain:manage",
		"platform:function:policy:manage",
	],
};

function itemLabels(groups: NavGroup[], groupLabel: string): string[] {
	return groups.find((g) => g.label === groupLabel)?.items.map((i) => i.label) ?? [];
}

function filter(user: typeof noneOfTheThree) {
	return filterNavigation(NAVIGATION_CONFIG, user, {
		messagingEnabled: true,
		canSeeScope,
		canAccessPath,
	});
}

describe("filterNavigation — the Functions nav group", () => {
	it("hides the group entirely for a user with none of the three permissions", () => {
		const result = filter(noneOfTheThree);
		expect(result.some((g) => g.label === "Functions")).toBe(false);
	});

	it("shows exactly the entries a partial-permission user's permissions admit", () => {
		const result = filter(onlyFunctionView);
		expect(itemLabels(result, "Functions")).toEqual(["Functions"]);
	});

	it("shows all three entries for a user holding all three permissions", () => {
		const result = filter(allThree);
		expect(itemLabels(result, "Functions")).toEqual([
			"Functions",
			"Domains",
			"Policies",
		]);
	});
});
