import { test as base, expect, type Locator, type Page } from "@playwright/test";
import { loginAndLand, ADMIN_EMAIL, ADMIN_PASSWORD } from "./admin.js";

/// A `page` already signed in as the bootstrap admin — every catalogue flow
/// starts past the login screen (mirrors `admin.ts`'s `adminPage`, kept
/// separate per the brief's "add what you need under a file named for your
/// group" so E2E-A's fixture file stays untouched).
export const test = base.extend<{ adminPage: Page }>({
    adminPage: async ({ page }, use) => {
        await loginAndLand(page, ADMIN_EMAIL, ADMIN_PASSWORD);
        await use(page);
    },
});

export { expect };

/// A short, run-unique, lowercase-alphanumeric suffix — the shape every code
/// field in this group requires (`^[a-z][a-z0-9-]*$`). Matches
/// `clientScoped.ts`'s `unique()` so a retried test never collides on a
/// uniqueness constraint within the run's one shared database.
export function unique(prefix: string): string {
    return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

/// Many create drawers in this group (Applications, Subscriptions,
/// Connections, Dispatch Pools, Event Types, Scheduled Jobs) label a field
/// with a bare `<label>` next to the input instead of PrimeVue's `for`/`id`
/// pairing. `getByLabel` finds nothing there; this locates the wrapping
/// `.form-field` (or a caller-supplied container class) by its visible
/// label text.
///
/// Matches specifically against `<label>` elements, NOT any text in the
/// container — `ProcessCreatePage.vue`'s Code field has a `<label>Code</label>`
/// AND, in the same `.form-field`, a help line reading "Format:
/// application:subdomain:process-name": a plain (case-insensitive,
/// substring) `getByText("Name")` matches that help text too, and being
/// first in DOM order over the real Name field, silently stole every fill
/// meant for Name and left the real field empty — the create then failed
/// validation with no test-visible signal beyond a toast. Scoping the
/// match to `<label>` elements is immune to that: the Code field's only
/// `<label>` reads exactly "Code".
export function bareField(page: Page, label: string, containerClass = ".form-field"): Locator {
    // Anchored on the field's own `<label>` text (allowing the trailing
    // required "*"), NOT a substring match anywhere in the container: a
    // substring match resolved `"Name"` to the *Code* field on the process
    // create page (its hint reads "application:subdomain:process-name") and
    // `"Schema"` to *Schema Type* on the add-schema page — both silently
    // filled the wrong control. Confirmed from a real page snapshot.
    const anchored = new RegExp(`^\\s*${escapeRegExp(label)}\\s*\\*?\\s*$`);
    return page.locator(containerClass).filter({ has: page.locator("label", { hasText: anchored }) }).first();
}

function escapeRegExp(s: string): string {
    return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/// [bareField], narrowed to the actual input/textarea control.
export function bareInput(page: Page, label: string, containerClass = ".form-field"): Locator {
    return bareField(page, label, containerClass).locator("input, textarea").first();
}

/// A hand-built PrimeVue `<Dialog>` (credentials-reveal, delete-confirmation
/// dialogs written as their own component) with this header text — these
/// render `role="dialog"`.
export function dialogWithHeader(page: Page, header: string | RegExp): Locator {
    return page.getByRole("dialog", { name: header });
}

/// A PrimeVue `confirm.require(...)` popup (`useConfirm()` — Activate/
/// Pause/Archive/Delete/Finalise/Deprecate everywhere in this group) with
/// this header text. **Not** the same role as [dialogWithHeader]:
/// `primevue/confirmdialog` renders `role="alertdialog"`, a distinct ARIA
/// role from a plain `role="dialog"` — `getByRole("dialog", ...)` will
/// never match it, confirmed against `primevue/confirmdialog/index.mjs`
/// (`role: "alertdialog"`) rather than assumed.
export function confirmWithHeader(page: Page, header: string | RegExp): Locator {
    return page.getByRole("alertdialog", { name: header });
}

/// Clicks a trigger button, then the accept button inside the
/// `confirm.require(...)` popup it opens (every destructive/state-changing
/// action across this group's detail drawers follows this exact two-click
/// shape — Activate/Deactivate/Pause/Resume/Suspend/Archive/Delete/
/// Finalise/Deprecate), and waits for the popup to close. Uses
/// [confirmWithHeader] (role `alertdialog`), NOT [dialogWithHeader] — a
/// `confirm.require` popup is not a plain `<Dialog>`, and asserting against
/// the wrong role would silently no-op (`toBeVisible()` timing out is loud,
/// but a role mix-up on a `Locator` built from the wrong role is a `pass`
/// on the query that never had a chance to fail — this project's own
/// testing policy warns against exactly that class of decorative check).
export async function confirmedAction(
    page: Page,
    triggerName: string | RegExp,
    dialogHeader: string | RegExp,
    acceptName: string | RegExp,
): Promise<void> {
    await page.getByRole("button", { name: triggerName, exact: true }).click();
    const dialog = confirmWithHeader(page, dialogHeader);
    await expect(dialog).toBeVisible();
    await dialog.getByRole("button", { name: acceptName, exact: true }).click();
    await expect(dialog).toBeHidden();
}

/// A DataTable row containing this text — every DataTable in this group
/// renders a real `<table>`, so a plain `tr` locator is a stable way to
/// assert a row's presence/absence after a reload without depending on
/// PrimeVue's internal ARIA role choices.
export function rowWithText(page: Page, text: string | RegExp): Locator {
    return page.locator("tr", { hasText: text });
}

/// The open create/edit drawer (`EntityDrawer` renders `role="complementary"`
/// — an aside, confirmed against a real strict-mode-violation error, not
/// assumed). Every list page in this group keeps its own header button
/// mounted UNDERNEATH the drawer while a `:id`/`new` child route is open
/// (nested routing — the parent list never unmounts), and several of those
/// header buttons carry the EXACT SAME label as the drawer's own submit
/// button (`"Create Pool"`, `"Create Connection"`, `"Create Subscription"`,
/// `"Create Event Type"`, `"Create Application"` all collide this way).
/// Scoping every create/edit-drawer button click to this landmark is what
/// makes the click unambiguous, regardless of whether a given screen
/// happens to collide today — a future rename that introduces a NEW
/// collision would silently break an unscoped `getByRole` click.
export function drawer(page: Page): Locator {
    return page.getByRole("complementary");
}

/// [drawer], narrowed to one named button inside it.
export function drawerButton(page: Page, name: string | RegExp): Locator {
    return drawer(page).getByRole("button", { name, exact: true });
}

/// Clicks `drawerButton(page, submitLabel)` and resolves once `urlSubstring`
/// (part of the create endpoint's path — `/applications`, `/bff/processes`,
/// â€¦) answers the click's own POST with a 2xx, returning the parsed body.
/// Waiting on the actual response — not a post-click URL regex — is what a
/// prior version of this file got bitten by: `/scheduled-jobs/create` and a
/// real `/scheduled-jobs/{tsid}` both satisfy a loose
/// `/\/scheduled-jobs\/[^/]+$/` pattern, so a submit that silently stayed on
/// the create screen (validation not yet satisfied, a slow save racing the
/// assertion) still read as success and poisoned every id derived from the
/// URL afterwards.
export async function submitDrawer<T = { id: string }>(
    page: Page,
    submitLabel: string | RegExp,
    urlSubstring: string,
): Promise<T> {
    const responsePromise = page.waitForResponse(
        (r) => r.url().includes(urlSubstring) && r.request().method() === "POST",
    );
    await drawerButton(page, submitLabel).click();
    const response = await responsePromise;
    expect(response.ok(), await response.text()).toBe(true);
    return (await response.json()) as T;
}

/// Opens the PrimeVue `Select`/`MultiSelect` combobox inside `container` and
/// clicks the option with this exact visible text. Works whether the panel
/// is appended to `body` (the default) or, via `appendTo="self"`, inside
/// the container — options are located from the page, not the container.
export async function choosePrimeOption(page: Page, container: Locator, optionText: string | RegExp): Promise<void> {
    await container.getByRole("combobox").click();
    await page.getByRole("option", { name: optionText }).first().click();
}

// ── Catalogue entity builders — each drives the real create UI so the
// flows for the entities named in the brief stay UI-driven. ─────────────

export interface CreatedApplication {
    code: string;
    name: string;
    id: string;
}

/// Creates an APPLICATION-type application via `/applications/new` and
/// returns its code/name/id (id parsed off the post-create detail URL).
export async function createApplication(page: Page, opts?: { code?: string; name?: string }): Promise<CreatedApplication> {
    const code = opts?.code ?? unique("e2e-app");
    const name = opts?.name ?? `E2E App ${code}`;

    await page.goto("/applications/new");
    await bareInput(page, "Code").fill(code);
    await bareInput(page, "Name").fill(name);
    const { id } = await submitDrawer(page, "Create Application", "/api/applications");
    await expect(page).toHaveURL(`/applications/${id}`);

    return { code, name, id };
}

/// Provisions a service account from an already-open application detail
/// drawer (`/applications/{id}`) and returns the service account's display
/// name — the value Connections' "Service Account" picker lists it under.
export async function provisionServiceAccount(page: Page): Promise<string> {
    await page.getByRole("button", { name: "Provision", exact: true }).click();
    const dialog = dialogWithHeader(page, "Service Account Provisioned");
    await expect(dialog).toBeVisible();
    const name = (
        await dialog
            .locator(".credential-item", { hasText: "Service Account" })
            .locator(".credential-value")
            .innerText()
    ).trim();
    await dialog.getByRole("button", { name: "I've saved the credentials" }).click();
    await expect(dialog).toBeHidden();
    return name;
}

export interface CreatedEventType {
    code: string;
    id: string;
}

/// Creates an event type under `applicationCode` via `/event-types/create`.
export async function createEventType(
    page: Page,
    applicationCode: string,
    opts?: { subdomain?: string; aggregate?: string; event?: string; name?: string; clientScoped?: boolean },
): Promise<CreatedEventType> {
    const subdomain = opts?.subdomain ?? "e2e";
    const aggregate = opts?.aggregate ?? "widget";
    const eventName = opts?.event ?? unique("created");
    const name = opts?.name ?? `E2E Event Type ${eventName}`;

    await page.goto("/event-types/create");
    await page.locator(".code-segment-group", { hasText: "Application" }).locator("input").fill(applicationCode);
    await page.locator(".code-segment-group", { hasText: "Subdomain" }).locator("input").fill(subdomain);
    await page.locator(".code-segment-group", { hasText: "Aggregate" }).locator("input").fill(aggregate);
    await page.locator(".code-segment-group", { hasText: "Event" }).locator("input").fill(eventName);
    await bareInput(page, "Name").fill(name);
    if (opts?.clientScoped) {
        // PrimeVue's ToggleSwitch keeps its real `<input>` hidden under the
        // visible slider, so clicking the input via getByLabel does not
        // reliably flip it (confirmed: the detail page read "Client
        // Scoped: No" after such a click). Clicking the `<label for=...>`
        // toggles it natively; asserting the checked state before submit
        // makes this helper self-verifying rather than silently wrong.
        await page.getByText("Client Scoped", { exact: true }).click();
        await expect(page.getByLabel("Client Scoped")).toBeChecked();
    }
    const { id } = await submitDrawer(page, "Create Event Type", "/bff/event-types");
    await expect(page).toHaveURL(`/event-types/${id}`);

    return { code: `${applicationCode}:${subdomain}:${aggregate}:${eventName}`, id };
}

/// Adds a schema to an event type and finalises it, so the event type has a
/// CURRENT spec version — a prerequisite for the Subscriptions create form,
/// which only lists event types with a CURRENT version.
export async function addAndFinaliseSchema(page: Page, eventTypeId: string, version = "1.0"): Promise<void> {
    await page.goto(`/event-types/${eventTypeId}/add-schema`);
    await bareInput(page, "Version").fill(version);
    // Not bareInput("Schema") — "Schema Type" is also a `.form-field` whose
    // label text contains the substring "Schema", and its field comes first
    // in the DOM, so a substring match on "Schema" would silently fill the
    // wrong field. The Schema Definition textarea is the only textarea on
    // this page.
    await page.locator("textarea").fill('{"$schema":"http://json-schema.org/draft-07/schema#","type":"object"}');
    await page.getByRole("button", { name: "Add Schema", exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/event-types/${eventTypeId}$`));

    // The Finalise/Deprecate row actions are icon-only Buttons with a
    // `v-tooltip` but no `label`/`aria-label` (verified against
    // `primevue/button/index.mjs`'s `defaultAriaLabel`, which falls back to
    // `undefined` when neither is set — `v-tooltip` never writes one) — so
    // `getByRole("button", { name: /Finalise/i })` has no accessible name to
    // match against and would never find it. The icon class is the only
    // stable handle.
    const row = page.locator("tr", { hasText: version });
    await row.locator("button:has(.pi-check)").click();
    const confirm = confirmWithHeader(page, "Finalise Schema");
    await expect(confirm).toBeVisible();
    await confirm.getByRole("button", { name: "Finalise", exact: true }).click();
    await expect(confirm).toBeHidden();
}

export interface CreatedDispatchPool {
    code: string;
    name: string;
    id: string;
}

/// Creates an anchor-level, ACTIVE-by-default dispatch pool via
/// `/dispatch-pools/new`.
export async function createDispatchPool(page: Page, opts?: { code?: string; name?: string }): Promise<CreatedDispatchPool> {
    const code = opts?.code ?? unique("e2e-pool");
    const name = opts?.name ?? `E2E Pool ${code}`;

    await page.goto("/dispatch-pools/new");
    await bareInput(page, "Code").fill(code);
    await bareInput(page, "Name").fill(name);
    const { id } = await submitDrawer(page, "Create Pool", "/api/dispatch-pools");
    await expect(page).toHaveURL(`/dispatch-pools/${id}`);

    return { code, name, id };
}

export interface CreatedConnection {
    code: string;
    name: string;
    id: string;
}

/// Creates an anchor-level connection via `/connections/new`, wired to the
/// given service account (its display name, as returned by
/// [provisionServiceAccount]).
export async function createConnection(
    page: Page,
    serviceAccountName: string,
    opts?: { code?: string; name?: string },
): Promise<CreatedConnection> {
    const code = opts?.code ?? unique("e2e-conn");
    const name = opts?.name ?? `E2E Connection ${code}`;

    await page.goto("/connections/new");
    await bareInput(page, "Code").fill(code);
    await bareInput(page, "Name").fill(name);
    const saField = bareField(page, "Service Account");
    await saField.getByRole("combobox").click();
    await page.getByRole("option", { name: serviceAccountName }).first().click();
    const { id } = await submitDrawer(page, "Create Connection", "/api/connections");
    await expect(page).toHaveURL(`/connections/${id}`);

    return { code, name, id };
}

export interface CreatedSubscription {
    code: string;
    name: string;
    id: string;
}

/// Creates a subscription via `/subscriptions/new`, wired to an
/// already-CURRENT event type ([createEventType] + [addAndFinaliseSchema])
/// by its display `name` and an already-active dispatch pool
/// ([createDispatchPool]) by its `name` — `isFormValid` on that drawer
/// requires both, plus code/name/endpoint/queue.
export async function createSubscription(
    page: Page,
    eventTypeName: string,
    dispatchPoolName: string,
    opts?: { code?: string; name?: string; endpoint?: string; queue?: string },
): Promise<CreatedSubscription> {
    const code = opts?.code ?? unique("e2e-sub");
    const name = opts?.name ?? `E2E Subscription ${code}`;
    const endpoint = opts?.endpoint ?? "https://example.com/e2e-hook";
    const queue = opts?.queue ?? "default";

    await page.goto("/subscriptions/new");
    await bareInput(page, "Code").fill(code);
    await bareInput(page, "Name").fill(name);

    // MultiSelect: PrimeVue's `role="combobox"` element here is a
    // zero-size readonly `<input>` that its own visible placeholder/label
    // div sits on top of — clicking the combobox locator directly gets
    // intercepted by that overlay (a real click-interception error, not a
    // timing fluke). Click the visible field container instead, then the
    // option, then Escape to close the panel so it doesn't cover the
    // fields below.
    await bareField(page, "Event Types").click();
    await page.getByRole("option", { name: new RegExp(eventTypeName) }).first().click();
    await page.keyboard.press("Escape");

    await bareInput(page, "Endpoint URL").fill(endpoint);
    await bareInput(page, "Queue").fill(queue);
    await choosePrimeOption(page, bareField(page, "Dispatch Pool"), new RegExp(dispatchPoolName));

    const { id } = await submitDrawer(page, "Create Subscription", "/api/subscriptions");
    await expect(page).toHaveURL(`/subscriptions/${id}`);

    return { code, name, id };
}

export interface CreatedScheduledJob {
    code: string;
    name: string;
    id: string;
}

/// Creates a platform-scoped (anchor-only) scheduled job via
/// `/scheduled-jobs/create`. `Scope` must be set to "Platform-scoped
/// (anchor only)" — the form refuses to submit with no scope chosen at
/// all (toast "Choose Platform or a client — this can't be changed after
/// creation").
export async function createScheduledJob(
    page: Page,
    opts?: { code?: string; name?: string; cron?: string; targetUrl?: string },
): Promise<CreatedScheduledJob> {
    const code = opts?.code ?? unique("e2e-job");
    const name = opts?.name ?? `E2E Scheduled Job ${code}`;
    const cron = opts?.cron ?? "0 0 0 1 1 *"; // once a year — never fires on its own during a run
    const targetUrl = opts?.targetUrl ?? "https://example.com/e2e-scheduled";

    await page.goto("/scheduled-jobs/create");
    await bareInput(page, "Code").fill(code);
    await bareInput(page, "Name").fill(name);
    await choosePrimeOption(page, bareField(page, "Scope"), "Platform-scoped (anchor only)");
    await bareInput(page, "Cron Expressions").fill(cron);
    await bareInput(page, "Target URL").fill(targetUrl);
    const { id } = await submitDrawer(page, "Create", "/api/scheduled-jobs");
    await expect(page).toHaveURL(`/scheduled-jobs/${id}`);

    return { code, name, id };
}

export interface CreatedProcess {
    code: string;
    name: string;
    id: string;
}

/// Creates a process via the standalone `/processes/create` page (a full
/// page, not an `EntityDrawer` — no `drawerButton` scoping needed; its own
/// "Create" submit button doesn't collide with the list's "Create Process"
/// header button because the list page unmounts on this route, unlike
/// every `:id`/`new` CHILD route above).
export async function createProcess(page: Page, opts?: { code?: string; name?: string; body?: string }): Promise<CreatedProcess> {
    const code = opts?.code ?? `${unique("e2e-app")}:e2e:widget-flow`;
    const name = opts?.name ?? `E2E Process ${code}`;
    const body = opts?.body ?? "graph TD\n  A[Start] --> B[End]";

    await page.goto("/processes/create");
    await bareInput(page, "Code").fill(code);
    await bareInput(page, "Name").fill(name);
    // Not bareInput("Mermaid source") — PrimeVue's Textarea narrowing via
    // `input, textarea` is fine here, but be explicit that this is the
    // page's only textarea (`.source-input`), matching addAndFinaliseSchema's
    // own note about ambiguous substring labels; kept as bareInput since
    // "Mermaid source" has no such collision on this page.
    await bareInput(page, "Mermaid source").fill(body);

    // A `page.waitForResponse` on `/bff/processes` timed out here in
    // practice even though the identical pattern works for every other
    // entity in this file — `ProcessCreatePage.vue`'s `save()` calls
    // `router.push` on success, so the URL is the reliable signal for this
    // one. `/processes/create` itself would satisfy a loose
    // `/\/processes\/[^/]+$/` (the exact race `submitDrawer`'s own comment
    // warns about for scheduled-jobs), so wait for a URL that is NOT the
    // create route.
    await page.getByRole("button", { name: "Create", exact: true }).click();
    await page.waitForURL((url) => /\/processes\/[^/]+$/.test(url.pathname) && !url.pathname.endsWith("/create"), {
        timeout: 15_000,
    });
    const id = new URL(page.url()).pathname.split("/").pop()!;

    return { code, name, id };
}

/// `FcDetailField.vue` renders `<div class="fc-detail-field"><span
/// class="fc-field-label">{label}</span><div
/// class="fc-detail-value">{value}</div></div>` with no `id`/`for` wiring —
/// `getByLabel` can't reach a read-only detail row. Scope `within` to a
/// section/drawer to avoid matching an unrelated field with the same label
/// elsewhere on the page (most detail drawers have a "Status" row).
export function detailValue(within: Locator, label: string): Locator {
    return within.locator(".fc-detail-field", { hasText: label }).locator(".fc-detail-value");
}
