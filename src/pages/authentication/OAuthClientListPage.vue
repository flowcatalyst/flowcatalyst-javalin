<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import { oauthClientsApi, type OAuthClient } from "@/api/oauth-clients";
import { useListState } from "@/composables/useListState";
import { useReturnTo } from "@/composables/useReturnTo";

const router = useRouter();
const { navigateToDetail } = useReturnTo();
const clients = ref<OAuthClient[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);

const { filters } = useListState({
	filters: {
		q: { type: "string", key: "q" },
	},
});

// Delete dialog state
const showDeleteDialog = ref(false);
const clientToDelete = ref<OAuthClient | null>(null);
const deleteLoading = ref(false);

const filteredClients = computed(() => {
	if (!filters.q.value) return clients.value;
	const query = filters.q.value.toLowerCase();
	return clients.value.filter(
		(client) =>
			client.clientName.toLowerCase().includes(query) ||
			client.clientId.toLowerCase().includes(query) ||
			(client.applications?.some((app) => app.name.toLowerCase().includes(query)) ?? false),
	);
});

onMounted(async () => {
	await loadClients();
});

async function loadClients() {
	loading.value = true;
	error.value = null;
	try {
		const response = await oauthClientsApi.list();
		clients.value = response.clients;
	} catch (e) {
		error.value =
			e instanceof Error ? e.message : "Failed to load OAuth clients";
	} finally {
		loading.value = false;
	}
}

function confirmDelete(client: OAuthClient) {
	clientToDelete.value = client;
	showDeleteDialog.value = true;
}

async function deleteClient() {
	if (!clientToDelete.value) return;

	deleteLoading.value = true;

	try {
		await oauthClientsApi.delete(clientToDelete.value.id);
		clients.value = clients.value.filter(
			(c) => c.id !== clientToDelete.value?.id,
		);
		showDeleteDialog.value = false;
		toast.success("Success", `OAuth client "${clientToDelete.value.clientName}" deleted`);
	} catch (e: unknown) {
	} finally {
		deleteLoading.value = false;
		clientToDelete.value = null;
	}
}

async function toggleActive(client: OAuthClient) {
	try {
		if (client.active) {
			await oauthClientsApi.deactivate(client.id);
			client.active = false;
			toast.success("Deactivated", `OAuth client "${client.clientName}" has been deactivated`);
		} else {
			await oauthClientsApi.activate(client.id);
			client.active = true;
			toast.success("Activated", `OAuth client "${client.clientName}" has been activated`);
		}
	} catch (e: unknown) {
	}
}

function getClientTypeSeverity(clientType: string) {
	return clientType === "PUBLIC" ? "info" : "warn";
}

function formatDate(dateString: string) {
	return new Date(dateString).toLocaleDateString();
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">OAuth Clients</h1>
        <p class="page-subtitle">
          Manage OAuth2/OIDC client registrations for applications that use FlowCatalyst as their
          identity provider.
        </p>
      </div>
      <Button
        label="Add OAuth Client"
        icon="pi pi-plus"
        @click="router.push('/authentication/oauth-clients/new')"
      />
    </header>

    <Message v-if="error" severity="error" class="error-message">{{ error }}</Message>

    <div class="fc-card">
      <div class="toolbar">
        <IconField class="search-wrapper">
          <InputIcon class="pi pi-search" />
          <InputText v-model="filters.q.value" placeholder="Search clients..." />
        </IconField>
      </div>

      <div v-if="loading" class="loading-container">
        <ProgressSpinner strokeWidth="3" />
      </div>

      <DataTable
        v-else
        :value="filteredClients"
        paginator
        :rows="100"
        :rowsPerPageOptions="[50, 100, 250, 500]"
        stripedRows
        emptyMessage="No OAuth clients found"
      >
        <Column field="clientName" header="Name" sortable>
          <template #body="{ data }">
            <span class="client-name">{{ data.clientName }}</span>
          </template>
        </Column>
        <Column field="clientId" header="Client ID" sortable>
          <template #body="{ data }">
            <code class="client-id-code">{{ data.clientId }}</code>
          </template>
        </Column>
        <Column field="clientType" header="Type" sortable>
          <template #body="{ data }">
            <Tag :value="data.clientType" :severity="getClientTypeSeverity(data.clientType)" />
          </template>
        </Column>
        <Column header="Applications">
          <template #body="{ data }">
            <div v-if="data.applications.length > 0" class="app-tags">
              <Tag
                v-for="app in data.applications.slice(0, 3)"
                :key="app.id"
                :value="app.name"
                severity="secondary"
                class="app-tag"
              />
              <span v-if="data.applications.length > 3" class="more-apps">
                +{{ data.applications.length - 3 }} more
              </span>
            </div>
            <span v-else class="text-muted">No restrictions</span>
          </template>
        </Column>
        <Column field="active" header="Status" sortable>
          <template #body="{ data }">
            <Tag
              :value="data.active ? 'Active' : 'Inactive'"
              :severity="data.active ? 'success' : 'secondary'"
            />
          </template>
        </Column>
        <Column field="createdAt" header="Created" sortable>
          <template #body="{ data }">
            {{ formatDate(data.createdAt) }}
          </template>
        </Column>
        <Column header="Actions" style="width: 120px">
          <template #body="{ data }">
            <div class="action-buttons">
              <Button
                icon="pi pi-eye"
                text
                rounded
                v-tooltip="'View Details'"
                @click="navigateToDetail(`/authentication/oauth-clients/${data.id}`)"
              />
              <Button
                :icon="data.active ? 'pi pi-ban' : 'pi pi-check-circle'"
                text
                rounded
                :severity="data.active ? 'warn' : 'success'"
                v-tooltip="data.active ? 'Deactivate' : 'Activate'"
                @click="toggleActive(data)"
              />
              <Button
                icon="pi pi-trash"
                text
                rounded
                severity="danger"
                v-tooltip="'Delete'"
                @click="confirmDelete(data)"
              />
            </div>
          </template>
        </Column>
      </DataTable>
    </div>

    <!-- Delete Confirmation Dialog -->
    <Dialog
      v-model:visible="showDeleteDialog"
      header="Delete OAuth Client"
      modal
      :style="{ width: '450px' }"
    >
      <div class="dialog-content">
        <p>
          Are you sure you want to delete the OAuth client
          <strong>{{ clientToDelete?.clientName }}</strong
          >?
        </p>

        <Message severity="warn" :closable="false" class="warning-message">
          Applications using this client will no longer be able to authenticate users.
        </Message>
      </div>

      <template #footer>
        <Button label="Cancel" text @click="showDeleteDialog = false" :disabled="deleteLoading" />
        <Button
          label="Delete"
          icon="pi pi-trash"
          severity="danger"
          @click="deleteClient"
          :loading="deleteLoading"
        />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.toolbar {
  margin-bottom: 16px;
}

.search-wrapper :deep(.pi-search) {
  color: #94a3b8;
}

.loading-container {
  display: flex;
  justify-content: center;
  padding: 60px;
}

.error-message {
  margin-bottom: 16px;
}

.client-name {
  font-weight: 500;
  color: #1e293b;
}

.client-id-code {
  background: #f1f5f9;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-family: monospace;
}

.app-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

.app-tag {
  font-size: 11px;
}

.more-apps {
  font-size: 12px;
  color: #64748b;
}

.text-muted {
  color: #94a3b8;
}

.dialog-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.warning-message {
  margin: 0;
}

.action-buttons {
  display: flex;
  flex-wrap: nowrap;
  gap: 0;
}
</style>
