<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import {
	dispatchPoolsApi,
	type DispatchPool,
	type DispatchPoolStatus,
} from "@/api/dispatch-pools";
import { useListState } from "@/composables/useListState";
import { useReturnTo } from "@/composables/useReturnTo";

const router = useRouter();
const { navigateToDetail } = useReturnTo();
const pools = ref<DispatchPool[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);

const { filters } = useListState({
	filters: {
		q:      { type: "string", key: "q" },
		status: { type: "string", key: "status" },
	},
});

const statusOptions = [
	{ label: "All Statuses", value: null },
	{ label: "Active", value: "ACTIVE" },
	{ label: "Suspended", value: "SUSPENDED" },
	{ label: "Archived", value: "ARCHIVED" },
];

const filteredPools = computed(() => {
	let result = pools.value;

	if (filters.status.value) {
		result = result.filter((pool) => pool.status === filters.status.value);
	}

	if (filters.q.value) {
		const query = filters.q.value.toLowerCase();
		result = result.filter(
			(pool) =>
				pool.code.toLowerCase().includes(query) ||
				pool.name.toLowerCase().includes(query) ||
				pool.clientIdentifier?.toLowerCase().includes(query),
		);
	}

	return result;
});

onMounted(async () => {
	await loadPools();
});

async function loadPools() {
	loading.value = true;
	error.value = null;
	try {
		const response = await dispatchPoolsApi.list();
		pools.value = response.pools;
	} catch (e) {
		error.value =
			e instanceof Error ? e.message : "Failed to load dispatch pools";
	} finally {
		loading.value = false;
	}
}

function getStatusSeverity(status: DispatchPoolStatus) {
	switch (status) {
		case "ACTIVE":
			return "success";
		case "SUSPENDED":
			return "warn";
		case "ARCHIVED":
			return "secondary";
		default:
			return "secondary";
	}
}

function formatDate(dateString: string) {
	return new Date(dateString).toLocaleDateString();
}

function getScopeLabel(pool: DispatchPool) {
	if (pool.clientIdentifier) {
		return pool.clientIdentifier;
	}
	return "Anchor-level";
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Dispatch Pools</h1>
        <p class="page-subtitle">Manage rate limiting and concurrency for dispatch jobs</p>
      </div>
      <Button label="Create Pool" icon="pi pi-plus" @click="router.push('/dispatch-pools/new')" />
    </header>

    <Message v-if="error" severity="error" class="error-message">{{ error }}</Message>

    <div class="fc-card">
      <div class="toolbar">
        <IconField class="search-wrapper">
          <InputIcon class="pi pi-search" />
          <InputText v-model="filters.q.value" placeholder="Search pools..." />
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
        :value="filteredPools"
        paginator
        :rows="100"
        :rowsPerPageOptions="[50, 100, 250, 500]"
        stripedRows
        emptyMessage="No dispatch pools found"
      >
        <Column field="code" header="Code" sortable>
          <template #body="{ data }">
            <code class="pool-code">{{ data.code }}</code>
          </template>
        </Column>
        <Column field="name" header="Name" sortable />
        <Column header="Client Scope" sortable>
          <template #body="{ data }">
            <span class="client-scope">{{ getScopeLabel(data) }}</span>
          </template>
        </Column>
        <Column field="rateLimit" header="Rate Limit" sortable>
          <template #body="{ data }">
            <span v-if="data.rateLimit != null">{{ data.rateLimit }}/min</span>
            <span v-else>—</span>
          </template>
        </Column>
        <Column field="concurrency" header="Concurrency" sortable />
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
              @click="navigateToDetail(`/dispatch-pools/${data.id}`)"
            />
            <Button
              icon="pi pi-pencil"
              text
              rounded
              v-tooltip="'Edit'"
              @click="navigateToDetail(`/dispatch-pools/${data.id}`)"
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

.pool-code {
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
