// Go screens: frontend/src/pages/applications/**, developer/**,
// event-types/**, events/**, processes/**, subscriptions/**, connections/**,
// dispatch-pools/**, dispatch-jobs/**, scheduled-jobs/**, and the two
// platform/debug/Raw*ListPage.vue debug views (read here as part of the
// dispatch-jobs / events flows they mirror).
// Parity scenarios (docs/spec/parity-harness.md S1/S2 groups) covering the
// same routes on the wire: the `catalogue` group's application/event-type/
// subscription/connection/dispatch-pool/dispatch-job/scheduled-job
// create/update/lifecycle scenarios.
//
// docs/spec/frontend-e2e.md §3 "catalogue". Assertions follow CLAUDE.md: the
// observable outcome after a reload, never "the drawer opened".
import {
    test,
    expect,
    unique,
    bareInput,
    confirmWithHeader,
    confirmedAction,
    rowWithText,
    createApplication,
    provisionServiceAccount,
    createEventType,
    addAndFinaliseSchema,
    createDispatchPool,
    createConnection,
    createSubscription,
    createScheduledJob,
    createProcess,
} from "../fixtures/catalogue.js";

test.describe("catalogue / applications", () => {
    test("a created application shows up after navigating away and back", async ({ adminPage: page }) => {
        const { code, name } = await createApplication(page);

        await page.goto("/applications");
        const row = rowWithText(page, code);
        await expect(row).toBeVisible();
        await expect(row).toContainText(name);
    });

    test("an edit persists after reload", async ({ adminPage: page }) => {
        const { id } = await createApplication(page);
        const newName = unique("renamed-app");

        await page.getByRole("button", { name: "Edit", exact: true }).click();
        await page.getByLabel("Name", { exact: true }).fill(newName);
        await page.getByRole("button", { name: "Save", exact: true }).click();
        await expect(page.getByRole("button", { name: "Edit", exact: true })).toBeVisible();

        await page.reload();
        // Not getByText(newName) — the same string also appears in the
        // underlying list row (a `:id` detail route is a nested route over
        // the list, which stays mounted underneath the open drawer), so a
        // bare text match is a strict-mode violation. The heading is unique
        // to the drawer.
        await expect(page.getByRole("heading", { name: newName })).toBeVisible();
    });

    test("deactivate then activate is reflected after reload", async ({ adminPage: page }) => {
        await createApplication(page);

        await confirmedAction(page, "Deactivate", "Deactivate Application", "Deactivate");
        await page.reload();
        // .first() — the list row underneath the drawer renders the same
        // status Tag text a second time (see the comment above).
        await expect(page.getByText("Inactive").first()).toBeVisible();

        await confirmedAction(page, "Activate", "Activate Application", "Activate");
        await page.reload();
        await expect(page.getByText("Active", { exact: true }).first()).toBeVisible();
    });

    test("provisioning a service account shows the secret once and never again", async ({ adminPage: page }) => {
        await createApplication(page);

        const name = await provisionServiceAccount(page);
        expect(name.length).toBeGreaterThan(0);

        // Reloaded proof: the section now shows "Provisioned", and nothing
        // resembling the one-time credential dialog is on the page.
        await page.reload();
        await expect(page.getByText("Provisioned").first()).toBeVisible();
        await expect(page.getByText("Client Secret (shown once)")).toHaveCount(0);
        await expect(page.getByRole("button", { name: "Provision", exact: true })).toHaveCount(0);
    });

    test("deleting an inactive application removes it from the list after reload", async ({ adminPage: page }) => {
        const { code } = await createApplication(page);

        await confirmedAction(page, "Deactivate", "Deactivate Application", "Deactivate");
        await confirmedAction(page, "Delete", "Delete Application", "Delete");

        await expect(page).toHaveURL(/\/applications$/);
        await page.reload();
        await expect(rowWithText(page, code)).toHaveCount(0);
    });
});

test.describe("catalogue / developer portal", () => {
    test("lists the platform application and opens its API docs / event types / processes tabs", async ({ adminPage: page }) => {
        await page.goto("/developer");
        await expect(page.getByRole("heading", { name: "Developer Portal" })).toBeVisible();
        // The platform itself is always registered and starred.
        const platformRow = rowWithText(page, "platform");
        await expect(platformRow).toBeVisible();

        await platformRow.click();
        await expect(page).toHaveURL(/\/developer\/applications\/[^/]+/);
        await expect(page.getByRole("tab", { name: "API Docs" })).toBeVisible();

        // `DeveloperApplicationDetailPage.vue`'s `<Tabs>` has no `lazy` prop,
        // so PrimeVue mounts every `<TabPanel>` up front and toggles them
        // with `v-show` (verified against `primevue/tabpanel/index.mjs`) —
        // the Event Types tab's and the Processes tab's Selects share the
        // literal placeholder "Subdomain", so both exist in the DOM at
        // once. A CSS `[placeholder=...]` attribute selector doesn't even
        // reach the right element (PrimeVue's `Select` renders the
        // placeholder as visible *text* inside a `.p-select-label` span,
        // not an HTML `placeholder` attribute — confirmed empirically: an
        // earlier `[placeholder="Subdomain"]:visible` version of this
        // assertion matched nothing at all). Each `TabPanel` root carries
        // `role="tabpanel"` and `data-p-active` (`primevue/tabpanel`'s
        // `active` prop) — scoping to the active one is the real, stable
        // handle.
        await page.getByRole("tab", { name: "Event Types" }).click();
        const eventTypesPanel = page.locator('[role="tabpanel"][data-p-active="true"]');
        await expect(eventTypesPanel.getByText("Subdomain", { exact: true })).toBeVisible();

        await page.getByRole("tab", { name: "Processes" }).click();
        const processesPanel = page.locator('[role="tabpanel"][data-p-active="true"]');
        await expect(processesPanel.getByText("Subdomain", { exact: true })).toBeVisible();
    });

    test("a created application appears in the developer list and its versions page starts empty", async ({ adminPage: page }) => {
        const { code, id } = await createApplication(page);

        await page.goto("/developer");
        await expect(rowWithText(page, code)).toBeVisible();

        await page.goto(`/developer/applications/${id}/versions`);
        await expect(page.getByRole("heading", { name: "API Versions" })).toBeVisible();
        await expect(page.getByText("No OpenAPI versions yet.")).toBeVisible();
    });
});

test.describe("catalogue / event types", () => {
    test("create, add and finalise a schema, deprecate, then archive — reflected after reload", async ({ adminPage: page }) => {
        const { code: appCode } = await createApplication(page);
        const { code, id } = await createEventType(page, appCode);

        await page.reload();
        // .first() — the list row underneath the open drawer renders the
        // same segmented code a second time.
        await expect(page.locator(".code-segment.event", { hasText: code.split(":")[3] }).first()).toBeVisible();

        await addAndFinaliseSchema(page, id, "1.0");
        await page.reload();
        await expect(page.getByText("CURRENT", { exact: true }).first()).toBeVisible();

        // Archive is refused (disabled) while a CURRENT schema exists.
        await expect(page.getByRole("button", { name: "Archive", exact: true })).toBeDisabled();

        // Icon-only button, no accessible name (see fixtures/catalogue.ts's
        // addAndFinaliseSchema comment) — the icon class is the only handle.
        const row = rowWithText(page, "1.0");
        await row.locator("button:has(.pi-ban)").click();
        await confirmWithHeader(page, "Deprecate Schema").getByRole("button", { name: "Deprecate", exact: true }).click();
        await page.reload();
        await expect(page.getByRole("button", { name: "Archive", exact: true })).toBeEnabled();

        await confirmedAction(page, "Archive", "Archive Event Type", "Archive");
        await page.reload();
        await expect(page.getByText("ARCHIVED", { exact: true }).first()).toBeVisible();

        // Archived event types can be deleted; the row then disappears.
        await confirmedAction(page, "Delete", "Delete Event Type", "Delete");
        await expect(page).toHaveURL(/\/event-types$/);
        await page.reload();
        await expect(rowWithText(page, appCode)).toHaveCount(0);
    });

    test("a client-scoped event type is tagged Yes on its detail page", async ({ adminPage: page }) => {
        // Expected to FAIL on both sides — a real finding, not a selector
        // problem. The create drawer sends `clientScoped: true` (the helper
        // asserts the switch is checked before submitting), but neither
        // server reads it on create: Go's eventtype/entity.go:208
        // hard-codes `ClientScoped: false` and its API only uses the flag to
        // filter lists; Java mirrors that. The detail page then reads
        // "Client Scoped: No". `test.fail` keeps the flow running so it
        // flips loudly the day either side honours the field.
        test.fail(true, "SPA defect on both sides: `clientScoped` is dropped on event-type create (Go eventtype/entity.go:208 hard-codes false; Java mirrors)");
        const { code: appCode } = await createApplication(page);
        const { id } = await createEventType(page, appCode, { clientScoped: true });

        await page.reload();
        await expect(page).toHaveURL(`/event-types/${id}`);
        // Scoped to the "Client Scoped" field row specifically — a bare
        // "Yes" is not unique among a detail page's several Yes/No fields.
        await expect(page.locator(".fc-detail-field", { hasText: "Client Scoped" })).toContainText("Yes");
    });
});

test.describe("catalogue / events", () => {
    test("the events list loads and its filters and debug view are usable", async ({ adminPage: page }) => {
        await page.goto("/events");
        await expect(page.getByRole("heading", { name: "Events" })).toBeVisible();
        await expect(page.getByPlaceholder("Search by source...")).toBeVisible();

        await page.goto("/platform/debug/events");
        await expect(page.getByRole("heading", { name: "Raw Events" })).toBeVisible();
        await expect(page.getByText("This is a debug view of the raw")).toBeVisible();
    });
});

test.describe("catalogue / processes", () => {
    test("create, edit, archive, then delete — reflected after reload", async ({ adminPage: page }) => {
        const { name, id } = await createProcess(page);

        await page.goto("/processes");
        await expect(rowWithText(page, name)).toBeVisible();

        await page.goto(`/processes/${id}/edit`);
        const newName = unique("renamed-process");
        await bareInput(page, "Name", ".form-field.full-row").fill(newName);
        await page.getByRole("button", { name: "Save", exact: true }).click();
        await expect(page).toHaveURL(new RegExp(`/processes/${id}$`));
        await page.reload();
        await expect(page.getByRole("heading", { name: newName })).toBeVisible();

        await page.getByRole("button", { name: "Archive", exact: true }).click();
        await page.reload();
        // .first() — the underlying list row (still mounted beneath the
        // open drawer) renders the same status Tag text a second time.
        await expect(page.getByText("ARCHIVED", { exact: true }).first()).toBeVisible();

        await confirmedAction(page, "Delete", "Delete Process", "Delete");
        await expect(page).toHaveURL(/\/processes$/);
        await page.reload();
        await expect(rowWithText(page, newName)).toHaveCount(0);
    });
});

test.describe("catalogue / dispatch pools", () => {
    test("create is active by default, suspend then delete (archive) — reflected after reload", async ({ adminPage: page }) => {
        const { code, name } = await createDispatchPool(page);

        await page.goto("/dispatch-pools");
        const row = rowWithText(page, code);
        await expect(row).toBeVisible();
        await expect(row).toContainText("ACTIVE");

        await row.click();
        await expect(page.getByRole("button", { name: "Suspend", exact: true })).toBeVisible();

        await confirmedAction(page, "Suspend", "Suspend Pool", "Suspend");
        await page.reload();
        // .first() — the underlying list row (still mounted beneath the
        // open drawer) renders the same status Tag text a second time.
        await expect(page.getByText("SUSPENDED", { exact: true }).first()).toBeVisible();

        // The confirm dialog says "This action will archive it." — it does
        // not. Both sides wire this button to a real DELETE
        // (`DeleteDispatchPool` on Java, `Repository.Delete` on Go) and the
        // row is gone from the list afterwards; ARCHIVED is a separate
        // operation this drawer never calls. Assert the true outcome — row
        // absence after reload — and leave the misleading copy to the
        // report.
        await confirmedAction(page, "Delete", "Delete Pool", "Delete");
        await expect(page).toHaveURL(/\/dispatch-pools$/);
        await page.reload();
        await expect(rowWithText(page, code)).toHaveCount(0);
    });
});

test.describe("catalogue / connections", () => {
    test("create wired to a provisioned service account, pause, then delete — reflected after reload", async ({ adminPage: page }) => {
        await createApplication(page);
        const serviceAccountName = await provisionServiceAccount(page);

        const { code, id } = await createConnection(page, serviceAccountName);
        await page.goto("/connections");
        await expect(rowWithText(page, code)).toBeVisible();

        await page.goto(`/connections/${id}`);
        await confirmedAction(page, "Pause", "Pause Connection", "Pause");
        await page.reload();
        // .first() — the underlying list row (still mounted beneath the
        // open drawer) renders the same status Tag text a second time.
        await expect(page.getByText("PAUSED", { exact: true }).first()).toBeVisible();

        await confirmedAction(page, "Delete", "Delete Connection", "Delete");
        await expect(page).toHaveURL(/\/connections$/);
        await page.reload();
        await expect(rowWithText(page, code)).toHaveCount(0);
    });
});

test.describe("catalogue / subscriptions", () => {
    test("create wired to a dispatch pool and a current event type, pause, then delete — reflected after reload", async ({ adminPage: page }) => {
        const { code: appCode } = await createApplication(page);
        const { code: eventTypeCode, id: eventTypeId } = await createEventType(page, appCode);
        await addAndFinaliseSchema(page, eventTypeId, "1.0");
        const { name: poolName } = await createDispatchPool(page);

        const eventTypeName = `E2E Event Type ${eventTypeCode.split(":")[3]}`;
        const { code, id } = await createSubscription(page, eventTypeName, poolName);

        await page.goto("/subscriptions");
        await expect(rowWithText(page, code)).toBeVisible();

        await page.goto(`/subscriptions/${id}`);
        await confirmedAction(page, "Pause", "Pause Subscription", "Pause");
        await page.reload();
        // .first() — the underlying list row (still mounted beneath the
        // open drawer) renders the same status Tag text a second time.
        await expect(page.getByText("PAUSED", { exact: true }).first()).toBeVisible();

        await confirmedAction(page, "Delete", "Delete Subscription", "Delete");
        await expect(page).toHaveURL(/\/subscriptions$/);
        await page.reload();
        await expect(rowWithText(page, code)).toHaveCount(0);
    });
});

test.describe("catalogue / dispatch jobs", () => {
    test("the dispatch jobs list and its debug view load with usable filters", async ({ adminPage: page }) => {
        await page.goto("/dispatch-jobs");
        await expect(page.getByRole("heading", { name: "Dispatch Jobs" })).toBeVisible();
        await expect(page.getByPlaceholder("Search by source...")).toBeVisible();
        await expect(page.getByRole("button", { name: /Requeue selected/ })).toBeDisabled();

        await page.goto("/platform/debug/dispatch-jobs");
        await expect(page.getByRole("heading", { name: "Raw Dispatch Jobs" })).toBeVisible();
    });
});

test.describe("catalogue / scheduled jobs", () => {
    test("create, fire now, then see the firing in the instances list and its detail page", async ({ adminPage: page }) => {
        const { code, id } = await createScheduledJob(page);

        await page.goto("/scheduled-jobs");
        await expect(rowWithText(page, code)).toBeVisible();

        await page.goto(`/scheduled-jobs/${id}`);
        const fired = page.waitForResponse((r) => r.url().includes(`/scheduled-jobs/${id}/fire`) && r.request().method() === "POST");
        await page.getByRole("button", { name: "Fire Now", exact: true }).click();
        const fireResponse = await fired;
        expect(fireResponse.ok()).toBe(true);
        const { id: instanceId } = (await fireResponse.json()) as { id: string };

        await page.reload();
        await expect(page.getByRole("row", { name: /MANUAL/ }).first()).toBeVisible();

        await page.goto(`/scheduled-jobs/${id}/instances`);
        await expect(rowWithText(page, "Manual")).toBeVisible();

        await page.goto(`/scheduled-jobs/instances/${instanceId}`);
        await expect(page.getByText(instanceId)).toBeVisible();
        await expect(page.getByText("MANUAL", { exact: true }).first()).toBeVisible();
    });

    test("pause then resume is reflected after reload", async ({ adminPage: page }) => {
        await createScheduledJob(page);

        await page.getByRole("button", { name: "Pause", exact: true }).click();
        await page.reload();
        // .first() — the underlying list row (still mounted beneath the
        // open drawer) renders the same status Tag text a second time.
        await expect(page.getByText("PAUSED", { exact: true }).first()).toBeVisible();

        await page.getByRole("button", { name: "Resume", exact: true }).click();
        await page.reload();
        await expect(page.getByText("ACTIVE", { exact: true }).first()).toBeVisible();
    });
});
