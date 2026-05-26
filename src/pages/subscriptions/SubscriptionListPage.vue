<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import {
	subscriptionsApi,
	type Subscription,
	type SubscriptionStatus,
} from "@/api/subscriptions";
import { useListState } from "@/composables/useListState";
import { useReturnTo } from "@/composables/useReturnTo";

const router = useRouter();
const { navigateToDetail } = useReturnTo();
const subscriptions = ref<Subscription[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);

const { filters } = useListState({
	filters: {
		q: { type: "string" as const, key: "q" },
		status: { type: "string" as const, key: "status" },
		application: { type: "array" as const, key: "app" },
	},
});

const statusOptions = [
	{ label: "All Statuses", value: "" },
	{ label: "Active", value: "ACTIVE" },
	{ label: "Paused", value: "PAUSED" },
];

const applicationOptions = computed(() => {
	const codes = new Set<string>();
	subscriptions.value.forEach((sub) => {
		if (sub.applicationCode) {
			codes.add(sub.applicationCode);
		}
	});
	return Array.from(codes)
		.toSorted()
		.map((code: string) => ({ label: code, value: code }));
});

const filteredSubscriptions = computed(() => {
	let result = subscriptions.value;

	if (filters.status.value) {
		result = result.filter((sub) => sub.status === filters.status.value);
	}

	if (filters.application.value.length > 0) {
		result = result.filter(
			(sub) =>
				sub.applicationCode &&
				filters.application.value.includes(sub.applicationCode),
		);
	}

	if (filters.q.value) {
		const query = filters.q.value.toLowerCase();
		result = result.filter(
			(sub) =>
				sub.code.toLowerCase().includes(query) ||
				sub.name.toLowerCase().includes(query) ||
				(sub.connectionId?.toLowerCase().includes(query) ?? false) ||
				sub.applicationCode?.toLowerCase().includes(query) ||
				sub.clientIdentifier?.toLowerCase().includes(query),
		);
	}

	return result;
});

onMounted(async () => {
	await loadSubscriptions();
});

async function loadSubscriptions() {
	loading.value = true;
	error.value = null;
	try {
		const response = await subscriptionsApi.list();
		subscriptions.value = response.subscriptions;
	} catch (e) {
		error.value =
			e instanceof Error ? e.message : "Failed to load subscriptions";
	} finally {
		loading.value = false;
	}
}

function getStatusSeverity(status: SubscriptionStatus) {
	switch (status) {
		case "ACTIVE":
			return "success";
		case "PAUSED":
			return "warn";
		default:
			return "secondary";
	}
}

function getModeLabel(mode: string) {
	switch (mode) {
		case "IMMEDIATE":
			return "Immediate";
		case "NEXT_ON_ERROR":
			return "Next on Error";
		case "BLOCK_ON_ERROR":
			return "Block on Error";
		default:
			return mode;
	}
}

function formatDate(dateString: string) {
	return new Date(dateString).toLocaleDateString();
}

function getScopeLabel(sub: Subscription) {
	if (sub.clientIdentifier) {
		return sub.clientIdentifier;
	}
	return "Anchor-level";
}

function getEventTypesLabel(sub: Subscription) {
	const count = sub.eventTypes?.length || 0;
	return `${count} event type${count !== 1 ? "s" : ""}`;
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Subscriptions</h1>
        <p class="page-subtitle">Manage event subscriptions and webhook routing</p>
      </div>
      <Button
        label="Create Subscription"
        icon="pi pi-plus"
        @click="router.push('/subscriptions/new')"
      />
    </header>

    <Message v-if="error" severity="error" class="error-message">{{ error }}</Message>

    <div class="fc-card">
      <div class="toolbar">
        <IconField class="search-wrapper">
          <InputIcon class="pi pi-search" />
          <InputText v-model="filters.q.value" placeholder="Search subscriptions..." />
        </IconField>
        <MultiSelect
          v-model="filters.application.value"
          :options="applicationOptions"
          optionLabel="label"
          optionValue="value"
          placeholder="Filter by application"
          class="application-filter"
          display="chip"
        />
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
        :value="filteredSubscriptions"
        paginator
        :rows="100"
        :rowsPerPageOptions="[50, 100, 250, 500]"
        stripedRows
        emptyMessage="No subscriptions found"
      >
        <Column field="code" header="Code" sortable>
          <template #body="{ data }">
            <code class="sub-code">{{ data.code }}</code>
          </template>
        </Column>
        <Column field="applicationCode" header="Application" sortable>
          <template #body="{ data }">
            <code v-if="data.applicationCode" class="app-code">{{ data.applicationCode }}</code>
            <span v-else class="no-app">—</span>
          </template>
        </Column>
        <Column field="name" header="Name" sortable />
        <Column header="Scope" sortable>
          <template #body="{ data }">
            <span class="scope-label">{{ getScopeLabel(data) }}</span>
          </template>
        </Column>
        <Column header="Event Types">
          <template #body="{ data }">
            <span class="event-types-count">{{ getEventTypesLabel(data) }}</span>
          </template>
        </Column>
        <Column field="dispatchPoolCode" header="Pool" sortable>
          <template #body="{ data }">
            <code class="pool-code">{{ data.dispatchPoolCode }}</code>
          </template>
        </Column>
        <Column header="Mode">
          <template #body="{ data }">
            <span class="mode-label">{{ getModeLabel(data.mode) }}</span>
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
              @click="navigateToDetail(`/subscriptions/${data.id}`)"
            />
            <Button
              icon="pi pi-pencil"
              text
              rounded
              v-tooltip="'Edit'"
              @click="navigateToDetail(`/subscriptions/${data.id}`)"
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

.application-filter {
  min-width: 220px;
}

.loading-container {
  display: flex;
  justify-content: center;
  padding: 60px;
}

.error-message {
  margin-bottom: 16px;
}

.sub-code {
  background: #f1f5f9;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 13px;
}

.app-code {
  background: #fef3c7;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  color: #92400e;
}

.no-app {
  color: #94a3b8;
}

.pool-code {
  background: #e0f2fe;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  color: #0369a1;
}

.scope-label {
  font-size: 13px;
  color: #64748b;
}

.event-types-count {
  font-size: 13px;
}

.mode-label {
  font-size: 12px;
  color: #64748b;
}
</style>
