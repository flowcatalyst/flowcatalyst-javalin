<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
	functionsApi,
	type FunctionResponse,
	type PoolSummaryResponse,
} from "@/api/functions";
import { applicationsApi, type Application } from "@/api/applications";
import { clientsApi, type Client } from "@/api/clients";
import { useAuthStore } from "@/stores/auth";
import { userScope } from "@/stores/permissions";
import { useListState } from "@/composables/useListState";
import { useTableFilters } from "@/composables/useTableFilters";

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();

const functions = ref<FunctionResponse[]>([]);
const total = ref(0);
const loading = ref(false);

const applications = ref<Application[]>([]);
const clients = ref<Client[]>([]);

const pools = ref<PoolSummaryResponse[]>([]);
const poolsLoading = ref(true);

// The client filter is anchor-only: a client-scoped caller already sees only
// its own client's functions via the server's reach rule, so there is
// nothing for that filter to narrow (mirrors how PortalUsersPage.vue detects
// anchor scope via the user's own clientId).
const isAnchor = computed(() => userScope(authStore.user) === "anchor");

const clientNameById = computed(() => {
	const map = new Map<string, string>();
	for (const c of clients.value) map.set(c.id, c.name);
	return map;
});

function ownerLabel(fn: FunctionResponse): string {
	if (!fn.clientId) return "Platform";
	return clientNameById.value.get(fn.clientId) ?? fn.clientId;
}

const applicationOptions = computed(() =>
	applications.value.map((a) => ({ label: a.name, value: a.code })),
);
const clientOptions = computed(() =>
	clients.value.map((c) => ({ label: c.name, value: c.id })),
);
const statusFilterOptions = [
	{ label: "Active", value: "ACTIVE" },
	{ label: "Disabled", value: "DISABLED" },
];

const listState = useListState(
	{
		filters: {
			applicationCode: { type: "string", key: "application" },
			clientId: { type: "string", key: "client" },
			status: { type: "string", key: "status" },
		},
		pageSize: 20,
	},
	() => load(),
);
const { filters, page, pageSize, onPage } = listState;

// Lazy table: the DataTable filter meta isn't bound — popup inputs write the
// listState refs directly and load() serializes them into API params.
const { activeFilterCount, clearAll } = useTableFilters(listState, [
	{ field: "applicationCode", param: "applicationCode" },
	{ field: "clientId", param: "clientId" },
	{ field: "status", param: "status" },
]);

async function load() {
	loading.value = true;
	try {
		// The list route has no application filter of its own — an
		// application-only FunctionAddressPattern ("<code>.*") is the pattern
		// it accepts for "every function of this application"
		// (FunctionAddressPattern.parse / Application variant).
		const result = await functionsApi.list({
			address: filters.applicationCode.value
				? `${filters.applicationCode.value}.*`
				: undefined,
			clientId: isAnchor.value
				? filters.clientId.value || undefined
				: undefined,
			status: (filters.status.value as "ACTIVE" | "DISABLED" | "") || undefined,
			page: page.value,
			size: pageSize.value,
		});
		functions.value = result.data;
		total.value = result.total;
	} catch (err) {
		console.error("Failed to load functions", err);
	} finally {
		loading.value = false;
	}
}

async function loadFilterOptions() {
	try {
		applications.value = (await applicationsApi.list()).applications;
	} catch (err) {
		console.error("Failed to load applications", err);
	}
	if (isAnchor.value) {
		try {
			clients.value = (await clientsApi.list()).clients;
		} catch (err) {
			console.error("Failed to load clients", err);
		}
	}
}

async function loadPools() {
	poolsLoading.value = true;
	try {
		pools.value = await functionsApi.pools();
	} catch (err) {
		console.error("Failed to load function pools", err);
	} finally {
		poolsLoading.value = false;
	}
}

onMounted(async () => {
	await loadFilterOptions();
	await load();
	void loadPools();
});

function viewFunction(fn: FunctionResponse) {
	void router.push({ path: `/functions/${fn.address}`, query: route.query });
}

function onRowClick(event: { data: FunctionResponse }) {
	viewFunction(event.data);
}

function statusSeverity(status: string): "success" | "secondary" {
	return status === "ACTIVE" ? "success" : "secondary";
}

function formatDate(s?: string): string {
	if (!s) return "—";
	return new Date(s).toLocaleString();
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Functions</h1>
        <p class="page-subtitle">Platform-run functions: code the platform hosts and invokes</p>
      </div>
    </header>

    <div class="fc-card table-card">
      <DataTable
        :value="functions"
        :loading="loading"
        :total-records="total"
        :rows="pageSize"
        :first="page * pageSize"
        lazy
        paginator
        :rows-per-page-options="[10, 20, 50, 100]"
        data-key="id"
        row-hover
        :rowClass="() => 'clickable-row'"
        stripedRows
        @row-click="onRowClick"
        @page="onPage"
      >
        <template #header>
          <FcTableToolbar
            :show-search="false"
            :active-filter-count="activeFilterCount"
            :has-active-filters="listState.hasActiveFilters.value"
            @clear-all="clearAll"
          >
            <template #filters>
              <FcFormField label="Application">
                <template #default="{ id: fieldId }">
                  <Select
                    :id="fieldId"
                    v-model="filters.applicationCode.value"
                    :options="applicationOptions"
                    optionLabel="label"
                    optionValue="value"
                    placeholder="All applications"
                    showClear
                    appendTo="self"
                  />
                </template>
              </FcFormField>
              <FcFormField v-if="isAnchor" label="Client">
                <template #default="{ id: fieldId }">
                  <Select
                    :id="fieldId"
                    v-model="filters.clientId.value"
                    :options="clientOptions"
                    optionLabel="label"
                    optionValue="value"
                    placeholder="All clients"
                    showClear
                    appendTo="self"
                  />
                </template>
              </FcFormField>
              <FcFormField label="Status">
                <template #default="{ id: fieldId }">
                  <Select
                    :id="fieldId"
                    v-model="filters.status.value"
                    :options="statusFilterOptions"
                    optionLabel="label"
                    optionValue="value"
                    placeholder="All statuses"
                    showClear
                    appendTo="self"
                  />
                </template>
              </FcFormField>
            </template>
          </FcTableToolbar>
        </template>
        <template #empty>No functions found</template>

        <Column field="address" header="Address" sortable>
          <template #body="{ data }">
            <code class="fn-address">{{ data.address }}</code>
          </template>
        </Column>
        <Column header="Owner">
          <template #body="{ data }">
            {{ ownerLabel(data) }}
          </template>
        </Column>
        <Column field="runtime" header="Runtime" />
        <Column header="Live Version">
          <template #body="{ data }">
            <span v-if="data.live">v{{ data.live.version }}</span>
            <span v-else>—</span>
          </template>
        </Column>
        <Column header="Status" sortable>
          <template #body="{ data }">
            <Tag :value="data.status" :severity="statusSeverity(data.status)" />
          </template>
        </Column>
        <Column header="Updated" sortable>
          <template #body="{ data }">
            {{ formatDate(data.updatedAt) }}
          </template>
        </Column>
      </DataTable>
    </div>

    <!-- Pools card (docs/spec/function-ui.md §2.5): a small, read-only panel.
         PoolSummaryResponse carries only a pool name and a host count — no
         per-host state breakdown and no "functions loaded" count exist on
         the wire (GET /api/function-pools), so those parts of the spec's
         wording aren't renderable from this endpoint; noted in the H1 report. -->
    <div class="fc-card pools-card">
      <h2 class="pools-title">Function Pools</h2>
      <ProgressSpinner v-if="poolsLoading" style="width: 24px; height: 24px" />
      <p v-else-if="pools.length === 0" class="pools-empty">No pools with a live host</p>
      <ul v-else class="pools-list">
        <li v-for="pool in pools" :key="pool.pool" class="pools-item">
          <span class="pool-name">{{ pool.pool }}</span>
          <span class="pool-hosts">{{ pool.hosts }} host{{ pool.hosts === 1 ? "" : "s" }}</span>
        </li>
      </ul>
    </div>

    <!-- Drawer outlet: detail child route renders over this list -->
    <RouterView v-slot="{ Component }">
      <component :is="Component" @changed="load" />
    </RouterView>
  </div>
</template>

<style scoped>
.table-card {
  padding: 0;
  overflow: hidden;
}

.fn-address {
  background: #f1f5f9;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 13px;
}

.pools-card {
  margin-top: 16px;
  padding: 16px 20px;
}

.pools-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
}

.pools-empty {
  margin: 0;
  color: #64748b;
  font-size: 13px;
}

.pools-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.pools-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #fafafa;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  font-size: 13px;
}

.pool-name {
  font-weight: 600;
  color: #1e293b;
}

.pool-hosts {
  color: #64748b;
}
</style>
