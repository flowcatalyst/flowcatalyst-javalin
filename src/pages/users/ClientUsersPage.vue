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
      <Button label="Add User" icon="pi pi-user-plus" @click="openCreate" />
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
