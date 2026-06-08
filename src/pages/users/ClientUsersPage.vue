<script setup lang="ts">
import { ref, computed, onMounted, watch } from "vue";
import { toast } from "@/utils/errorBus";
import { getErrorMessage } from "@/utils/errors";
import { useAuthStore } from "@/stores/auth";
import { useClientOptions } from "@/composables/useClientOptions";
import {
	usersApi,
	type User,
	type RoleAssignment,
	type ApplicationAccessGrant,
	type AvailableApplication,
	type BulkImportUserRow,
	type BulkImportResponse,
} from "@/api/users";
import { rolesApi, type Role } from "@/api/roles";

// Client-administrator user management. Scoped by construction: every endpoint
// it calls (list / create / assign-roles / reset / 2FA / activate) is one the
// backend permits a non-anchor administrator to use against their own client's
// users — so it never surfaces an anchor-only control or hits a scope 403. The
// platform user-management page (anchor scope) stays separate.

const authStore = useAuthStore();
const { ensureLoaded: ensureClients, getLabel: getClientLabel } = useClientOptions();

const users = ref<User[]>([]);
const loading = ref(false);
const initialLoading = ref(true);
const totalRecords = ref(0);
const availableRoles = ref<Role[]>([]);

// Filters
const q = ref("");
const selectedStatus = ref<string | null>(null);
const clientFilter = ref<string | null>(null);
const page = ref(0);
const pageSize = ref(100);

const statusOptions = [
	{ label: "Active", value: "active" },
	{ label: "Inactive", value: "inactive" },
];

// The clients this administrator can act in. With more than one, expose a client
// filter and a target picker on create; with one, everything is implicit.
const clientOptions = computed(() =>
	authStore.accessibleClients.map((id) => ({ label: getClientLabel(id), value: id })),
);
const isMultiClient = computed(() => authStore.accessibleClients.length > 1);
const defaultClientId = computed(
	() => authStore.accessibleClients[0] ?? authStore.user?.clientId ?? "",
);

const hasActiveFilters = computed(
	() => !!q.value || selectedStatus.value !== null || clientFilter.value !== null,
);

onMounted(async () => {
	await Promise.all([ensureClients(), loadRoles(), loadUsers()]);
});

watch([q, selectedStatus, clientFilter], () => {
	page.value = 0;
	loadUsers();
});

async function loadUsers() {
	loading.value = true;
	try {
		const response = await usersApi.list({
			type: "USER",
			clientId: clientFilter.value || undefined,
			active:
				selectedStatus.value === "active"
					? true
					: selectedStatus.value === "inactive"
						? false
						: undefined,
			q: q.value || undefined,
			page: page.value,
			pageSize: pageSize.value,
			sortField: "createdAt",
			sortOrder: "asc",
		});
		users.value = response.principals;
		totalRecords.value = response.total;
	} catch (error) {
		toast.error("Error", getErrorMessage(error, "Request failed"));
	} finally {
		loading.value = false;
		initialLoading.value = false;
	}
}

async function loadRoles() {
	try {
		availableRoles.value = (await rolesApi.list()).items;
	} catch (error) {
		console.error("Failed to load roles:", error);
	}
}

function onPage(event: { page: number; rows: number }) {
	page.value = event.page;
	pageSize.value = event.rows;
	loadUsers();
}

function clearFilters() {
	q.value = "";
	selectedStatus.value = null;
	clientFilter.value = null;
}

function clientName(id: string | null): string {
	return id ? getClientLabel(id) : "—";
}

function formatDate(dateStr: string | undefined | null) {
	return dateStr ? new Date(dateStr).toLocaleDateString() : "—";
}

// ── Create user ─────────────────────────────────────────────────────────────
const showCreate = ref(false);
const createForm = ref({ email: "", name: "", password: "", clientId: "" });
const createSaving = ref(false);

function openCreate() {
	createForm.value = {
		email: "",
		name: "",
		password: "",
		clientId: defaultClientId.value,
	};
	showCreate.value = true;
}

async function submitCreate() {
	const f = createForm.value;
	if (!f.email.trim() || !f.name.trim()) {
		toast.error("Error", "Name and email are required");
		return;
	}
	if (!f.clientId) {
		toast.error("Error", "Select a client");
		return;
	}
	createSaving.value = true;
	try {
		await usersApi.createClientUser({
			email: f.email.trim(),
			name: f.name.trim(),
			password: f.password || undefined,
			clientId: f.clientId,
		});
		toast.success("User created", `${f.name} was added`);
		showCreate.value = false;
		await loadUsers();
	} catch (error) {
		toast.error("Create failed", getErrorMessage(error, "Request failed"));
	} finally {
		createSaving.value = false;
	}
}

// ── Manage roles ────────────────────────────────────────────────────────────
const showRoles = ref(false);
const rolesUser = ref<User | null>(null);
const roleAssignments = ref<RoleAssignment[]>([]);
const appGrants = ref<ApplicationAccessGrant[]>([]);
const selectedRoleNames = ref<string[]>([]);
const rolesSaving = ref(false);

// Roles the admin may assign: application-scoped roles for an application the
// target user can reach (or already-assigned ones, so they stay visible). This
// mirrors the platform detail page and the backend's assertAssignableRoles —
// platform roles and out-of-reach app roles never appear.
const assignableRoleOptions = computed(() => {
	const accessibleCodes = new Set(appGrants.value.map((g) => g.applicationCode));
	const assignedNames = new Set(roleAssignments.value.map((r) => r.roleName));
	return availableRoles.value
		.filter((r) => accessibleCodes.has(r.applicationCode) || assignedNames.has(r.name))
		.map((r) => ({ label: r.displayName, value: r.name }));
});

async function openRoles(user: User) {
	rolesUser.value = user;
	roleAssignments.value = [];
	appGrants.value = [];
	selectedRoleNames.value = [];
	showRoles.value = true;
	try {
		const [roles, apps] = await Promise.all([
			usersApi.getRoles(user.id),
			usersApi.getApplicationAccess(user.id),
		]);
		roleAssignments.value = roles.roles;
		appGrants.value = apps.applications;
		const assignable = new Set(assignableRoleOptions.value.map((o) => o.value));
		selectedRoleNames.value = roles.roles
			.map((r) => r.roleName)
			.filter((n) => assignable.has(n));
	} catch (error) {
		toast.error("Error", getErrorMessage(error, "Request failed"));
	}
}

async function saveRoles() {
	if (!rolesUser.value) return;
	rolesSaving.value = true;
	try {
		// A SET of the roles this admin manages; the backend preserves the user's
		// existing platform / other-application roles automatically.
		await usersApi.assignRoles(rolesUser.value.id, selectedRoleNames.value);
		toast.success("Roles updated", `Roles saved for ${rolesUser.value.name}`);
		showRoles.value = false;
		await loadUsers();
	} catch (error) {
		toast.error("Save failed", getErrorMessage(error, "Request failed"));
	} finally {
		rolesSaving.value = false;
	}
}

// ── Bulk import (CSV onboarding) ────────────────────────────────────────────
const showImport = ref(false);
const importFileName = ref("");
const importRows = ref<BulkImportUserRow[]>([]);
const importClientId = ref("");
const importing = ref(false);
const importError = ref("");
const importResult = ref<BulkImportResponse | null>(null);

function openImport() {
	importFileName.value = "";
	importRows.value = [];
	importClientId.value = defaultClientId.value;
	importError.value = "";
	importResult.value = null;
	showImport.value = true;
}

function downloadTemplate() {
	const csv =
		"Full Name,Email,Roles (| separated)\n" +
		"Jane Doe,jane.doe@example.com,role-one|role-two\n" +
		"John Smith,john.smith@example.com,\n";
	const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
	const url = URL.createObjectURL(blob);
	const a = document.createElement("a");
	a.href = url;
	a.download = "user-import-template.csv";
	a.click();
	URL.revokeObjectURL(url);
}

// Minimal CSV field parser: handles quoted fields and escaped quotes ("").
function parseCsvLine(line: string): string[] {
	const out: string[] = [];
	let cur = "";
	let inQuotes = false;
	for (let i = 0; i < line.length; i++) {
		const c = line[i];
		if (inQuotes) {
			if (c === '"') {
				if (line[i + 1] === '"') {
					cur += '"';
					i++;
				} else {
					inQuotes = false;
				}
			} else {
				cur += c;
			}
		} else if (c === '"') {
			inQuotes = true;
		} else if (c === ",") {
			out.push(cur);
			cur = "";
		} else {
			cur += c;
		}
	}
	out.push(cur);
	return out;
}

function parseCsv(text: string): BulkImportUserRow[] {
	const lines = text.split(/\r?\n/).filter((l) => l.trim() !== "");
	if (lines.length === 0) return [];
	// Skip the header row when the first line looks like column titles.
	const first = (lines[0] ?? "").toLowerCase();
	const startIdx = first.includes("email") && first.includes("name") ? 1 : 0;
	const rows: BulkImportUserRow[] = [];
	for (let i = startIdx; i < lines.length; i++) {
		const cols = parseCsvLine(lines[i] ?? "");
		const name = (cols[0] ?? "").trim();
		const email = (cols[1] ?? "").trim();
		const roles = (cols[2] ?? "")
			.split("|")
			.map((r) => r.trim())
			.filter(Boolean);
		if (!name && !email) continue;
		rows.push({ name, email, roles });
	}
	return rows;
}

async function onFileChange(event: Event) {
	importError.value = "";
	importResult.value = null;
	importRows.value = [];
	const input = event.target as HTMLInputElement;
	const file = input.files?.[0];
	if (!file) return;
	importFileName.value = file.name;
	try {
		importRows.value = parseCsv(await file.text());
		if (importRows.value.length === 0) {
			importError.value = "No user rows found in the file.";
		}
	} catch (e) {
		importError.value = getErrorMessage(e, "Could not read the file.");
	}
}

async function submitImport() {
	importError.value = "";
	if (!importClientId.value) {
		importError.value = "Select a client.";
		return;
	}
	if (importRows.value.length === 0) {
		importError.value = "Choose a CSV file with at least one user.";
		return;
	}
	importing.value = true;
	try {
		importResult.value = await usersApi.bulkImport(
			importClientId.value,
			importRows.value,
		);
		const r = importResult.value;
		toast.success(
			"Import complete",
			`${r.created} created, ${r.skipped} skipped, ${r.failed} failed`,
		);
		await loadUsers();
	} catch (e) {
		importError.value = getErrorMessage(e, "Import failed.");
	} finally {
		importing.value = false;
	}
}

// ── Manage applications ─────────────────────────────────────────────────────
const showApps = ref(false);
const appsUser = ref<User | null>(null);
const currentApps = ref<ApplicationAccessGrant[]>([]);
const availableApps = ref<AvailableApplication[]>([]);
const selectedAppIds = ref<string[]>([]);
const appsSaving = ref(false);

// The available-applications endpoint is already bounded server-side to the
// applications the admin's client can access, so the picker only ever offers
// grantable apps.
const appOptions = computed(() =>
	availableApps.value.map((a) => ({ label: a.name || a.code, value: a.id })),
);

async function openApps(user: User) {
	appsUser.value = user;
	currentApps.value = [];
	availableApps.value = [];
	selectedAppIds.value = [];
	showApps.value = true;
	try {
		const [granted, available] = await Promise.all([
			usersApi.getApplicationAccess(user.id),
			usersApi.getAvailableApplications(user.id),
		]);
		currentApps.value = granted.applications;
		availableApps.value = available.applications;
		// Preselect only the grants this admin can manage (within the client's
		// applications); any out-of-reach grants are preserved server-side.
		const availIds = new Set(available.applications.map((a) => a.id));
		selectedAppIds.value = granted.applications
			.map((g) => g.applicationId)
			.filter((id) => availIds.has(id));
	} catch (error) {
		toast.error("Error", getErrorMessage(error, "Request failed"));
	}
}

async function saveApps() {
	if (!appsUser.value) return;
	appsSaving.value = true;
	try {
		await usersApi.assignApplicationAccess(appsUser.value.id, selectedAppIds.value);
		toast.success(
			"Applications updated",
			`Application access saved for ${appsUser.value.name}`,
		);
		showApps.value = false;
		await loadUsers();
	} catch (error) {
		toast.error("Save failed", getErrorMessage(error, "Request failed"));
	} finally {
		appsSaving.value = false;
	}
}

// ── Reset password / 2FA ────────────────────────────────────────────────────
const showReset = ref(false);
const resetUser = ref<User | null>(null);
const resetPassword = ref("");
const resetBusy = ref(false);

function openReset(user: User) {
	resetUser.value = user;
	resetPassword.value = "";
	showReset.value = true;
}

async function submitReset() {
	if (!resetUser.value) return;
	if (resetPassword.value.length < 8) {
		toast.error("Error", "Password must be at least 8 characters");
		return;
	}
	resetBusy.value = true;
	try {
		await usersApi.resetPassword(resetUser.value.id, resetPassword.value);
		toast.success("Password reset", `New password set for ${resetUser.value.name}`);
		showReset.value = false;
	} catch (error) {
		toast.error("Reset failed", getErrorMessage(error, "Request failed"));
	} finally {
		resetBusy.value = false;
	}
}

async function sendReset(user: User) {
	try {
		const r = await usersApi.sendPasswordReset(user.id);
		toast.success("Reset email sent", r.message);
	} catch (error) {
		toast.error("Send failed", getErrorMessage(error, "Request failed"));
	}
}

async function resetTwoFactor(user: User) {
	try {
		const r = await usersApi.resetTwoFactor(user.id);
		toast.success("2FA reset", r.message);
	} catch (error) {
		toast.error("Reset failed", getErrorMessage(error, "Request failed"));
	}
}

async function toggleActive(user: User) {
	try {
		if (user.active) {
			await usersApi.deactivate(user.id);
			toast.success("Deactivated", `${user.name} deactivated`);
		} else {
			await usersApi.activate(user.id);
			toast.success("Activated", `${user.name} activated`);
		}
		await loadUsers();
	} catch (error) {
		toast.error("Error", getErrorMessage(error, "Request failed"));
	}
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">User Management</h1>
        <p class="page-subtitle">Manage users for your client</p>
      </div>
      <div class="header-actions">
        <Button
          label="Import CSV"
          icon="pi pi-upload"
          outlined
          @click="openImport"
        />
        <Button label="Add User" icon="pi pi-user-plus" @click="openCreate" />
      </div>
    </header>

    <!-- Filters -->
    <div class="fc-card filter-card">
      <div class="filter-row">
        <div class="filter-group">
          <label>Search</label>
          <IconField>
            <InputIcon class="pi pi-search" />
            <InputText v-model="q" placeholder="Search by name or email..." class="filter-input" />
          </IconField>
        </div>

        <div v-if="isMultiClient" class="filter-group">
          <label>Client</label>
          <Select
            v-model="clientFilter"
            :options="clientOptions"
            optionLabel="label"
            optionValue="value"
            placeholder="All Clients"
            :showClear="true"
            class="filter-input"
          />
        </div>

        <div class="filter-group">
          <label>Status</label>
          <Select
            v-model="selectedStatus"
            :options="statusOptions"
            optionLabel="label"
            optionValue="value"
            placeholder="All Statuses"
            :showClear="true"
            class="filter-input"
          />
        </div>

        <div class="filter-actions">
          <Button
            v-if="hasActiveFilters"
            label="Clear Filters"
            icon="pi pi-filter-slash"
            text
            severity="secondary"
            @click="clearFilters"
          />
        </div>
      </div>
    </div>

    <!-- Table -->
    <div class="fc-card table-card">
      <div v-if="initialLoading" class="loading-container">
        <ProgressSpinner strokeWidth="3" />
      </div>

      <DataTable
        v-else
        :value="users"
        :loading="loading"
        :paginator="true"
        :first="page * pageSize"
        :rows="pageSize"
        :totalRecords="totalRecords"
        :rowsPerPageOptions="[50, 100, 250, 500]"
        :lazy="true"
        :showCurrentPageReport="true"
        currentPageReportTemplate="Showing {first} to {last} of {totalRecords} users"
        stripedRows
        size="small"
        @page="onPage"
      >
        <Column field="name" header="Name" style="width: 20%">
          <template #body="{ data }">
            <span class="user-name">{{ data.name }}</span>
          </template>
        </Column>

        <Column field="email" header="Email" style="width: 24%">
          <template #body="{ data }">
            <span class="user-email">{{ data.email || '—' }}</span>
          </template>
        </Column>

        <Column header="Client" style="width: 16%">
          <template #body="{ data }">
            <span class="client-name-text">{{ clientName(data.clientId) }}</span>
          </template>
        </Column>

        <Column field="active" header="Status" style="width: 10%">
          <template #body="{ data }">
            <Tag :value="data.active ? 'Active' : 'Inactive'" :severity="data.active ? 'success' : 'danger'" />
          </template>
        </Column>

        <Column field="roles" header="Roles" style="width: 16%">
          <template #body="{ data }">
            <div class="roles-container">
              <Tag
                v-for="role in (data.roles || []).slice(0, 2)"
                :key="role"
                :value="role.split(':').pop()"
                severity="secondary"
                class="role-tag"
              />
              <span v-if="(data.roles || []).length > 2" class="more-roles">
                +{{ data.roles.length - 2 }} more
              </span>
            </div>
          </template>
        </Column>

        <Column field="createdAt" header="Created" style="width: 8%">
          <template #body="{ data }">
            <span class="date-text">{{ formatDate(data.createdAt) }}</span>
          </template>
        </Column>

        <Column header="Actions" style="width: 6%">
          <template #body="{ data }">
            <div class="action-buttons">
              <Button
                icon="pi pi-shield"
                text
                rounded
                severity="secondary"
                @click="openRoles(data)"
                v-tooltip.top="'Manage roles'"
              />
              <Button
                icon="pi pi-th-large"
                text
                rounded
                severity="secondary"
                @click="openApps(data)"
                v-tooltip.top="'Manage applications'"
              />
              <Button
                icon="pi pi-key"
                text
                rounded
                severity="secondary"
                @click="openReset(data)"
                v-tooltip.top="'Reset password'"
              />
              <Button
                icon="pi pi-envelope"
                text
                rounded
                severity="secondary"
                @click="sendReset(data)"
                v-tooltip.top="'Send reset email'"
              />
              <Button
                icon="pi pi-mobile"
                text
                rounded
                severity="secondary"
                @click="resetTwoFactor(data)"
                v-tooltip.top="'Reset 2FA (re-trigger onboarding)'"
              />
              <Button
                :icon="data.active ? 'pi pi-ban' : 'pi pi-check-circle'"
                text
                rounded
                :severity="data.active ? 'danger' : 'success'"
                @click="toggleActive(data)"
                v-tooltip.top="data.active ? 'Deactivate' : 'Activate'"
              />
            </div>
          </template>
        </Column>

        <template #empty>
          <div class="empty-message">
            <i class="pi pi-users"></i>
            <span>No users found</span>
            <Button v-if="hasActiveFilters" label="Clear filters" link @click="clearFilters" />
          </div>
        </template>
      </DataTable>
    </div>

    <!-- Create user dialog -->
    <Dialog v-model:visible="showCreate" header="Add User" modal :style="{ width: '30rem' }">
      <div class="dialog-form">
        <div class="field">
          <label for="cu-name">Name</label>
          <InputText id="cu-name" v-model="createForm.name" class="w-full" />
        </div>
        <div class="field">
          <label for="cu-email">Email</label>
          <InputText id="cu-email" v-model="createForm.email" class="w-full" />
        </div>
        <div class="field">
          <label for="cu-password">Password</label>
          <Password
            id="cu-password"
            v-model="createForm.password"
            toggleMask
            :feedback="false"
            inputClass="w-full"
            class="w-full"
          />
          <small class="hint">Leave blank to require the user to set it via a reset email.</small>
        </div>
        <div v-if="isMultiClient" class="field">
          <label for="cu-client">Client</label>
          <Select
            id="cu-client"
            v-model="createForm.clientId"
            :options="clientOptions"
            optionLabel="label"
            optionValue="value"
            class="w-full"
          />
        </div>
      </div>
      <template #footer>
        <Button label="Cancel" text severity="secondary" @click="showCreate = false" />
        <Button label="Create" icon="pi pi-check" :loading="createSaving" @click="submitCreate" />
      </template>
    </Dialog>

    <!-- Manage roles dialog -->
    <Dialog
      v-model:visible="showRoles"
      :header="rolesUser ? `Roles — ${rolesUser.name}` : 'Roles'"
      modal
      :style="{ width: '34rem' }"
    >
      <div class="dialog-form">
        <p class="dialog-note">
          Only roles for applications this user's client can access are shown. Platform roles are
          managed by platform administrators.
        </p>
        <MultiSelect
          v-model="selectedRoleNames"
          :options="assignableRoleOptions"
          optionLabel="label"
          optionValue="value"
          display="chip"
          filter
          placeholder="Select roles"
          class="w-full"
          :showToggleAll="false"
        />
        <p v-if="assignableRoleOptions.length === 0" class="hint">
          No assignable roles — this client has no applications with assignable roles.
        </p>
      </div>
      <template #footer>
        <Button label="Cancel" text severity="secondary" @click="showRoles = false" />
        <Button label="Save Roles" icon="pi pi-check" :loading="rolesSaving" @click="saveRoles" />
      </template>
    </Dialog>

    <!-- Manage applications dialog -->
    <Dialog
      v-model:visible="showApps"
      :header="appsUser ? `Applications — ${appsUser.name}` : 'Applications'"
      modal
      :style="{ width: '34rem' }"
    >
      <div class="dialog-form">
        <p class="dialog-note">
          Grant this user access to applications your client is entitled to.
        </p>
        <MultiSelect
          v-model="selectedAppIds"
          :options="appOptions"
          optionLabel="label"
          optionValue="value"
          display="chip"
          filter
          placeholder="Select applications"
          class="w-full"
          :showToggleAll="false"
        />
        <p v-if="appOptions.length === 0" class="hint">
          No applications available — your client has no applications to grant.
        </p>
      </div>
      <template #footer>
        <Button label="Cancel" text severity="secondary" @click="showApps = false" />
        <Button
          label="Save Applications"
          icon="pi pi-check"
          :loading="appsSaving"
          @click="saveApps"
        />
      </template>
    </Dialog>

    <!-- Reset password dialog -->
    <Dialog
      v-model:visible="showReset"
      :header="resetUser ? `Reset password — ${resetUser.name}` : 'Reset password'"
      modal
      :style="{ width: '28rem' }"
    >
      <div class="dialog-form">
        <div class="field">
          <label for="rp-pw">New password</label>
          <Password
            id="rp-pw"
            v-model="resetPassword"
            toggleMask
            :feedback="true"
            inputClass="w-full"
            class="w-full"
          />
        </div>
        <p class="hint">
          Or close this and use “Send reset email” to let the user choose their own password.
        </p>
      </div>
      <template #footer>
        <Button label="Cancel" text severity="secondary" @click="showReset = false" />
        <Button label="Set Password" icon="pi pi-check" :loading="resetBusy" @click="submitReset" />
      </template>
    </Dialog>

    <!-- Bulk import (CSV) dialog -->
    <Dialog
      v-model:visible="showImport"
      header="Import users from CSV"
      modal
      :style="{ width: '42rem' }"
    >
      <div class="dialog-form">
        <p class="dialog-note">
          Upload a CSV with columns <strong>Full Name</strong>, <strong>Email</strong>, and
          <strong>Roles</strong> (pipe&#8209;separated, e.g. <code>role-one|role-two</code>).
          New users are created and emailed an invite to set their password; existing users are
          skipped. Roles must be ones your client can access.
        </p>

        <div class="import-toolbar">
          <Button
            label="Download template"
            icon="pi pi-download"
            text
            size="small"
            @click="downloadTemplate"
          />
        </div>

        <div v-if="isMultiClient" class="field">
          <label for="imp-client">Client</label>
          <Select
            id="imp-client"
            v-model="importClientId"
            :options="clientOptions"
            optionLabel="label"
            optionValue="value"
            class="w-full"
          />
        </div>

        <div class="field">
          <label for="imp-file">CSV file</label>
          <input
            id="imp-file"
            type="file"
            accept=".csv,text/csv"
            class="file-input"
            @change="onFileChange"
          />
          <small v-if="importFileName" class="hint">
            {{ importFileName }} — {{ importRows.length }} user(s) parsed.
          </small>
        </div>

        <p v-if="importError" class="error-text">{{ importError }}</p>

        <!-- Results -->
        <div v-if="importResult" class="import-results">
          <div class="import-summary">
            <Tag :value="`${importResult.created} created`" severity="success" />
            <Tag :value="`${importResult.skipped} skipped`" severity="warn" />
            <Tag :value="`${importResult.failed} failed`" :severity="importResult.failed ? 'danger' : 'secondary'" />
          </div>
          <DataTable
            v-if="importResult.results.some((r) => r.status !== 'created')"
            :value="importResult.results.filter((r) => r.status !== 'created')"
            size="small"
            class="import-table"
          >
            <Column field="row" header="Row" style="width: 4rem" />
            <Column field="email" header="Email" />
            <Column header="Result">
              <template #body="{ data }">
                <Tag
                  :value="data.status"
                  :severity="data.status === 'exists' ? 'warn' : 'danger'"
                />
              </template>
            </Column>
            <Column field="message" header="Detail" />
          </DataTable>
        </div>
      </div>
      <template #footer>
        <Button label="Close" text severity="secondary" @click="showImport = false" />
        <Button
          label="Import"
          icon="pi pi-upload"
          :loading="importing"
          :disabled="importRows.length === 0"
          @click="submitImport"
        />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.filter-card {
  margin-bottom: 24px;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: flex-end;
}

.filter-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 200px;
}

.filter-group label {
  font-size: 13px;
  font-weight: 500;
  color: #475569;
}

.filter-input {
  width: 100%;
}

.filter-actions {
  margin-left: auto;
}

.table-card {
  padding: 0;
  overflow: hidden;
}

.loading-container {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 60px;
}

.user-name {
  font-weight: 500;
  color: #1e293b;
}

.user-email {
  color: #64748b;
  font-size: 13px;
}

.client-name-text {
  font-size: 13px;
  color: #1e293b;
}

.roles-container {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

.role-tag {
  font-size: 11px;
}

.more-roles {
  font-size: 12px;
  color: #64748b;
}

.date-text {
  font-size: 13px;
  color: #64748b;
}

.action-buttons {
  display: flex;
  gap: 2px;
}

.empty-message {
  text-align: center;
  padding: 48px 24px;
  color: #64748b;
}

.empty-message i {
  font-size: 48px;
  display: block;
  margin-bottom: 16px;
  color: #cbd5e1;
}

.empty-message span {
  display: block;
  margin-bottom: 12px;
}

.dialog-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding-top: 8px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field label {
  font-size: 13px;
  font-weight: 500;
  color: #475569;
}

.dialog-note {
  font-size: 13px;
  color: #64748b;
  margin: 0;
}

.hint {
  font-size: 12px;
  color: #94a3b8;
}

.w-full {
  width: 100%;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.import-toolbar {
  display: flex;
  justify-content: flex-start;
}

.file-input {
  font-size: 13px;
}

.error-text {
  margin: 0;
  font-size: 13px;
  color: #b91c1c;
}

.import-results {
  border-top: 1px solid #e2e8f0;
  padding-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.import-summary {
  display: flex;
  gap: 8px;
}

.import-table {
  font-size: 13px;
}

:deep(.p-datatable .p-datatable-thead > tr > th) {
  background: #f8fafc;
  color: #475569;
  font-weight: 600;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

@media (max-width: 1024px) {
  .filter-row {
    flex-direction: column;
    align-items: stretch;
  }

  .filter-group {
    min-width: 100%;
  }

  .filter-actions {
    margin-left: 0;
    margin-top: 8px;
  }
}
</style>
