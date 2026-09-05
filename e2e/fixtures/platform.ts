import { test as base, expect, type Locator, type Page } from "@playwright/test";
import { loginAndLand, ADMIN_EMAIL, ADMIN_PASSWORD } from "./admin.js";

/// A `page` already signed in as the bootstrap admin.
export const test = base.extend<{ adminPage: Page }>({
    adminPage: async ({ page }, use) => {
        await loginAndLand(page, ADMIN_EMAIL, ADMIN_PASSWORD);
        await use(page);
    },
});

export { expect };

export function unique(prefix: string): string {
    return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

/// Every confirmation/detail dialog in this group (CORS add/delete, the
/// audit-log and login-attempt "view details" dialogs) is a hand-written
/// PrimeVue `<Dialog>`, not a `useConfirm()` popup — `role="dialog"`
/// throughout (unlike the catalogue group's detail-drawer actions, which
/// are `useConfirm()`/`role="alertdialog"`; see `fixtures/catalogue.ts`).
export function dialogWithHeader(page: Page, header: string | RegExp): Locator {
    return page.getByRole("dialog", { name: header });
}

export function rowWithText(page: Page, text: string | RegExp): Locator {
    return page.locator("tr", { hasText: text });
}
