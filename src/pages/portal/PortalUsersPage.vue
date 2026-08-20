<script setup lang="ts">
// Portal Users — per-client management of the portal identity plane
// (docs/portal-identity-plan.md Phase 2.5 v2). Visible to platform admins
// (any client, via the picker) and to client administrators holding the
// platform:portal-administrator role (their own client(s)).
import { ref, computed, onMounted, watch } from "vue";
import { useConfirm } from "primevue/useconfirm";
import { toast } from "@/utils/errorBus";
import { portalUsersApi, type PortalUser } from "@/api/portal-users";
import { clientsApi, type Client } from "@/api/clients";
import { useAuthStore } from "@/stores/auth";
import { getErrorMessage } from "@/utils/errors";

const confirm = useConfirm();
const authStore = useAuthStore();

const clients = ref<Client[]>([]);
const selectedClientId = ref<string>("");
const portalUsers = ref<PortalUser[]>([]);
const loading = ref(false);

const isAnchor = computed(() => !authStore.user?.clientId);

const clientOptions = computed(() => {
	if (isAnchor.value) {
		return clients.value.map((c) => ({ label: c.name, value: c.id }));
	}
	return authStore.accessibleClients.map((id) => ({
		label: clients.value.find((c) => c.id === id)?.name || id,
		value: id,
	}));
});

onMounted(async () => {
	try {
		const response = await clientsApi.list();
		clients.value = response.clients || [];
	} catch {
	}
	// Client admins land on their own client; anchors pick one.
	if (!isAnchor.value) {
		selectedClientId.value =
			authStore.accessibleClients[0] ?? authStore.user?.clientId ?? "";
	} else if (clientOptions.value.length === 1) {
		selectedClientId.value = clientOptions.value[0]?.value ?? "";
	}
});

watch(selectedClientId, () => {
	if (selectedClientId.value) void loadPortalUsers();
});

async function loadPortalUsers() {
	if (!selectedClientId.value) return;
	loading.value = true;
	try {
		const response = await portalUsersApi.list(selectedClientId.value);
		portalUsers.value = response.portalUsers;
	} catch (e: unknown) {
		toast.error("Error", getErrorMessage(e, "Failed to load portal users"));
	} finally {
		loading.value = false;
	}
}

function formatDate(dateStr: string | undefined | null) {
	if (!dateStr) return "—";
	return new Date(dateStr).toLocaleString();
}

// ── Invite ───────────────────────────────────────────────────────────────

const showInviteDialog = ref(false);
const inviteEmail = ref("");
const inviteName = ref("");
const inviting = ref(false);
const inviteEmailValid = computed(() => /.+@.+\..+/.test(inviteEmail.value.trim()));

function openInviteDialog() {
	inviteEmail.value = "";
	inviteName.value = "";
	showInviteDialog.value = true;
}

// Honest outcome copy: a re-ensure of an identity that already holds a
// password deliberately sends nothing (the account is live) — say so
// instead of implying an invite went out.
function inviteOutcomeMessage(result: {
	created: boolean;
	invited: boolean;
	ssoManaged?: boolean;
	hasPassword: boolean;
}): string {
	if (result.created) {
		if (result.invited) return "Portal user created and invited";
		if (result.ssoManaged)
			return "Portal user created — their organisation signs them in (no invite mail configured)";
		return "Portal user created (no mailer configured — invite not sent)";
	}
	if (result.invited) return "Portal user already existed; invite re-sent";
	if (result.hasPassword)
		return "Portal user already has a password — nothing sent. They can sign in, or use Forgot password on the portal sign-in page.";
	if (result.ssoManaged)
		return "Portal user already existed — their organisation signs them in";
	return "Portal user already existed";
}

async function sendInvite() {
	if (!inviteEmailValid.value || inviting.value) return;
	inviting.value = true;
	try {
		const result = await portalUsersApi.ensure({
			clientId: selectedClientId.value,
			email: inviteEmail.value.trim(),
			name: inviteName.value.trim() || undefined,
		});
		toast.success(
			"Success",
			inviteOutcomeMessage(result),
		);
		showInviteDialog.value = false;
		await loadPortalUsers();
	} catch (e: unknown) {
		toast.error("Error", getErrorMessage(e, "Failed to invite portal user"));
	} finally {
		inviting.value = false;
	}
}

// ── Row actions ──────────────────────────────────────────────────────────

async function toggleStatus(user: PortalUser) {
	const suspend = user.status === "ACTIVE";
	try {
		if (suspend) {
			await portalUsersApi.deactivate(user.identityId, selectedClientId.value);
			toast.success("Success", `${user.email} suspended`);
		} else {
			await portalUsersApi.activate(user.identityId, selectedClientId.value);
			toast.success("Success", `${user.email} reactivated`);
		}
		await loadPortalUsers();
	} catch (e: unknown) {
		toast.error("Error", getErrorMessage(e, "Failed to update status"));
	}
}

function confirmDelete(user: PortalUser) {
	confirm.require({
		message: `Delete portal user "${user.email}"? They will no longer be able to sign in to this client's portal. This cannot be undone.`,
		header: "Delete portal user",
		icon: "pi pi-exclamation-triangle",
		acceptClass: "p-button-danger",
		acceptLabel: "Delete",
		accept: async () => {
			try {
				await portalUsersApi.remove(user.identityId, selectedClientId.value);
				toast.success("Success", `${user.email} deleted`);
				await loadPortalUsers();
			} catch (e: unknown) {
				toast.error("Error", getErrorMessage(e, "Failed to delete portal user"));
			}
		},
	});
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h1>Portal Users</h1>
        <p class="page-subtitle">
          The portal end-user population for a client — separate identities
          from platform users, managed per client.
        </p>
      </div>
      <Button
        label="Invite Portal User"
        icon="pi pi-user-plus"
        :disabled="!selectedClientId"
        @click="openInviteDialog"
      />
    </div>

    <div class="toolbar">
      <Select
        v-model="selectedClientId"
        :options="clientOptions"
        optionLabel="label"
        optionValue="value"
        placeholder="Select a client"
        filter
        class="client-select"
      />
    </div>

    <DataTable
      :value="portalUsers"
      :loading="loading"
      dataKey="identityId"
      paginator
      :rows="25"
      :rowsPerPageOptions="[25, 50, 100]"
    >
      <template #empty>
        <span v-if="!selectedClientId">Select a client to view its portal users.</span>
        <span v-else>No portal users for this client yet.</span>
      </template>
      <Column field="email" header="Email" sortable />
      <Column field="name" header="Name" sortable>
        <template #body="{ data }">{{ data.name || "—" }}</template>
      </Column>
      <Column field="status" header="Status" sortable>
        <template #body="{ data }">
          <Tag
            :value="data.status"
            :severity="data.status === 'ACTIVE' ? 'success' : 'warn'"
          />
        </template>
      </Column>
      <Column field="source" header="Source" sortable>
        <template #body="{ data }">
          <Tag
            :value="data.source === 'JIT' ? 'SSO' : 'Invited'"
            severity="info"
          />
        </template>
      </Column>
      <Column field="hasPassword" header="Password">
        <template #body="{ data }">
          <i
            :class="data.hasPassword ? 'pi pi-check text-green-500' : 'pi pi-minus text-muted'"
          />
        </template>
      </Column>
      <Column field="lastLoginAt" header="Last Login" sortable>
        <template #body="{ data }">{{ formatDate(data.lastLoginAt) }}</template>
      </Column>
      <Column field="createdAt" header="Created" sortable>
        <template #body="{ data }">{{ formatDate(data.createdAt) }}</template>
      </Column>
      <Column header="" :style="{ width: '8rem' }">
        <template #body="{ data }">
          <div class="row-actions">
            <Button
              :icon="data.status === 'ACTIVE' ? 'pi pi-ban' : 'pi pi-check-circle'"
              :title="data.status === 'ACTIVE' ? 'Suspend' : 'Reactivate'"
              text
              rounded
              @click="toggleStatus(data)"
            />
            <Button
              icon="pi pi-trash"
              title="Delete"
              text
              rounded
              severity="danger"
              @click="confirmDelete(data)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <Dialog
      v-model:visible="showInviteDialog"
      header="Invite Portal User"
      modal
      :style="{ width: '28rem' }"
    >
      <div class="field">
        <label for="inviteEmail">Email</label>
        <InputText id="inviteEmail" v-model="inviteEmail" type="email" class="w-full" autofocus />
      </div>
      <div class="field">
        <label for="inviteName">Name (optional)</label>
        <InputText id="inviteName" v-model="inviteName" class="w-full" />
      </div>
      <small class="field-help">
        Creates the portal identity for this client and sends a set-password
        invite. Safe to repeat — a lost invite is re-sent.
      </small>
      <template #footer>
        <Button label="Cancel" text :disabled="inviting" @click="showInviteDialog = false" />
        <Button
          label="Send Invite"
          :loading="inviting"
          :disabled="!inviteEmailValid"
          @click="sendInvite"
        />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.page-container {
	padding: 1.5rem;
}
.page-header {
	display: flex;
	justify-content: space-between;
	align-items: flex-start;
	margin-bottom: 1rem;
	gap: 1rem;
}
.page-header h1 {
	margin: 0 0 0.25rem;
	font-size: 1.4rem;
}
.page-subtitle {
	margin: 0;
	color: var(--p-text-muted-color);
	font-size: 0.9rem;
}
.toolbar {
	margin-bottom: 1rem;
}
.client-select {
	min-width: 20rem;
}
.field {
	margin-bottom: 1rem;
	display: flex;
	flex-direction: column;
	gap: 0.35rem;
}
.field-help {
	color: var(--p-text-muted-color);
}
.row-actions {
	display: flex;
	gap: 0.25rem;
}
.text-muted {
	color: var(--p-text-muted-color);
}
</style>
