<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import {
	connectionsApi,
	type Connection,
	type ConnectionStatus,
} from "@/api/connections";
import { useListState } from "@/composables/useListState";
import { useReturnTo } from "@/composables/useReturnTo";

const router = useRouter();
const { navigateToDetail } = useReturnTo();
const connections = ref<Connection[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);

const { filters } = useListState({
	filters: {
		q: { type: "string" as const, key: "q" },
		status: { type: "string" as const, key: "status" },
	},
});

const statusOptions = [
	{ label: "All Statuses", value: "" },
	{ label: "Active", value: "ACTIVE" },
	{ label: "Paused", value: "PAUSED" },
];

const filteredConnections = computed(() => {
	let result = connections.value;

	if (filters.status.value) {
		result = result.filter((conn) => conn.status === filters.status.value);
	}

	if (filters.q.value) {
		const query = filters.q.value.toLowerCase();
		result = result.filter(
			(conn) =>
				conn.code.toLowerCase().includes(query) ||
				conn.name.toLowerCase().includes(query) ||
				conn.clientIdentifier?.toLowerCase().includes(query),
		);
	}

	return result;
});

onMounted(async () => {
	await loadConnections();
});

async function loadConnections() {
	loading.value = true;
	error.value = null;
	try {
		const response = await connectionsApi.list();
		connections.value = response.connections;
	} catch (e) {
		error.value =
			e instanceof Error ? e.message : "Failed to load connections";
	} finally {
		loading.value = false;
	}
}

function getStatusSeverity(status: ConnectionStatus) {
	switch (status) {
		case "ACTIVE":
			return "success";
		case "PAUSED":
			return "warn";
		default:
			return "secondary";
	}
}

function formatDate(dateString: string) {
	return new Date(dateString).toLocaleDateString();
}

function getScopeLabel(conn: Connection) {
	if (conn.clientIdentifier) {
		return conn.clientIdentifier;
	}
	return "Anchor-level";
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Connections</h1>
        <p class="page-subtitle">Manage webhook connections for event delivery</p>
      </div>
      <Button label="Create Connection" icon="pi pi-plus" @click="router.push('/connections/new')" />
    </header>

    <Message v-if="error" severity="error" class="error-message">{{ error }}</Message>

    <div class="fc-card">
      <div class="toolbar">
        <IconField class="search-wrapper">
          <InputIcon class="pi pi-search" />
          <InputText v-model="filters.q.value" placeholder="Search connections..." />
        </IconField>
        <Select
          v-model="filters.status.value"
          :options="statusOptions"
          optionLabel="label"
          optionValue="value"
          placeholder="Filter by status"
          class="status-filter"
        />
      </div>

      <div v-if="loading" class="loading-container">
        <ProgressSpinner strokeWidth="3" />
      </div>

      <DataTable
        v-else
        :value="filteredConnections"
        paginator
        :rows="100"
        :rowsPerPageOptions="[50, 100, 250, 500]"
        stripedRows
        emptyMessage="No connections found"
      >
        <Column field="code" header="Code" sortable>
          <template #body="{ data }">
            <code class="conn-code">{{ data.code }}</code>
          </template>
        </Column>
        <Column field="name" header="Name" sortable />
        <Column header="Scope" sortable>
          <template #body="{ data }">
            <span class="client-scope">{{ getScopeLabel(data) }}</span>
          </template>
        </Column>
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
              @click="navigateToDetail(`/connections/${data.id}`)"
            />
            <Button
              icon="pi pi-pencil"
              text
              rounded
              v-tooltip="'Edit'"
              @click="navigateToDetail(`/connections/${data.id}`)"
            />
          </template>
        </Column>
      </DataTable>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 16px;
  margin-bottom: 16px;
}

.search-wrapper {
  flex: 1;
}

.search-wrapper :deep(.pi-search) {
  color: #94a3b8;
}

.status-filter {
  min-width: 180px;
}

.loading-container {
  display: flex;
  justify-content: center;
  padding: 60px;
}

.error-message {
  margin-bottom: 16px;
}

.conn-code {
  background: #f1f5f9;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 13px;
}

.client-scope {
  font-size: 13px;
  color: #475569;
}
</style>
