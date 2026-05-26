<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import { useListState } from "@/composables/useListState";
import { useReturnTo } from "@/composables/useReturnTo";
import { clientsApi, type Client } from "@/api/clients";

const PAGE_SIZE = 100;

const router = useRouter();
const { navigateToDetail } = useReturnTo();
const clients = ref<Client[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);
const hasMore = ref(false);

const { filters, page } = useListState(
	{
		filters: {
			q: { type: "string", key: "q" },
		},
		pageSize: PAGE_SIZE,
	},
	() => loadClients(),
);

const filteredClients = computed(() => {
	if (!filters.q.value) return clients.value;
	const query = filters.q.value.toLowerCase();
	return clients.value.filter(
		(client) =>
			client.identifier.toLowerCase().includes(query) ||
			client.name.toLowerCase().includes(query),
	);
});

onMounted(async () => {
	await loadClients();
});

async function loadClients() {
	loading.value = true;
	error.value = null;
	try {
		const response = await clientsApi.list({ page: page.value, pageSize: PAGE_SIZE });
		clients.value = response.clients;
		hasMore.value = response.clients.length > 0;
	} catch (e) {
		error.value = e instanceof Error ? e.message : "Failed to load clients";
	} finally {
		loading.value = false;
	}
}

async function prevPage() {
	page.value--;
	await loadClients();
}

async function nextPage() {
	page.value++;
	await loadClients();
}

function getStatusSeverity(status: string) {
	switch (status) {
		case "ACTIVE":
			return "success";
		case "SUSPENDED":
			return "warn";
		case "INACTIVE":
			return "secondary";
		default:
			return "secondary";
	}
}

function formatDate(dateString: string) {
	return new Date(dateString).toLocaleDateString();
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Clients</h1>
        <p class="page-subtitle">Manage customer clients and their configurations</p>
      </div>
      <Button label="Create Client" icon="pi pi-plus" @click="router.push('/clients/new')" />
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
        stripedRows
        emptyMessage="No clients found"
      >
        <Column field="identifier" header="Identifier" sortable>
          <template #body="{ data }">
            <code class="client-code">{{ data.identifier }}</code>
          </template>
        </Column>
        <Column field="name" header="Name" sortable />
        <Column field="status" header="Status" sortable>
          <template #body="{ data }">
            <Tag :value="data.status" :severity="getStatusSeverity(data.status)" />
          </template>
        </Column>
        <Column field="createdAt" header="Created" sortable>
          <template #body="{ data }">
            {{ formatDate(data.createdAt) }}
          </template>
        </Column>
        <Column header="Actions" style="width: 120px">
          <template #body="{ data }">
            <Button
              icon="pi pi-eye"
              text
              rounded
              v-tooltip="'View'"
              @click="navigateToDetail(`/clients/${data.id}`)"
            />
            <Button
              icon="pi pi-pencil"
              text
              rounded
              v-tooltip="'Edit'"
              @click="navigateToDetail(`/clients/${data.id}`)"
            />
          </template>
        </Column>
      </DataTable>

      <div v-if="!loading" class="pagination">
        <Button
          v-if="page > 0"
          label="Previous"
          icon="pi pi-chevron-left"
          text
          @click="prevPage"
        />
        <Button
          v-if="hasMore"
          label="Next"
          icon="pi pi-chevron-right"
          iconPos="right"
          text
          @click="nextPage"
        />
      </div>
    </div>
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

.client-code {
  background: #f1f5f9;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 13px;
}

.pagination {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}
</style>
