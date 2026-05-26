<script setup lang="ts">
import { ref, onMounted } from "vue";
import { useListState } from "@/composables/useListState";
import ClientFilter from "@/components/ClientFilter.vue";
import {
	dispatchJobsApi,
	type DispatchJobRead as DispatchJob,
	type DispatchJobsListParams,
} from "@/api/dispatch-jobs";

interface FilterOption {
	label: string;
	value: string;
}

const sizeOptions = [50, 100, 200, 500, 1000];

const { filters, pageSize, hasActiveFilters, clearFilters, syncToUrl, withSuppressed } =
	useListState({
		filters: {
			clients: { type: "array", key: "clients" },
			applications: { type: "array", key: "applications" },
			subdomains: { type: "array", key: "subdomains" },
			aggregates: { type: "array", key: "aggregates" },
			codes: { type: "array", key: "codes" },
			statuses: { type: "array", key: "statuses" },
			search: { type: "string", key: "q" },
		},
		pageSize: 200,
	});

function buildParams(): DispatchJobsListParams {
	return {
		size: pageSize.value,
		clientIds: filters.clients.value.length ? filters.clients.value : undefined,
		statuses: filters.statuses.value.length ? filters.statuses.value : undefined,
		applications: filters.applications.value.length ? filters.applications.value : undefined,
		subdomains: filters.subdomains.value.length ? filters.subdomains.value : undefined,
		aggregates: filters.aggregates.value.length ? filters.aggregates.value : undefined,
		codes: filters.codes.value.length ? filters.codes.value : undefined,
		source: filters.search.value || undefined,
	};
}

const dispatchJobs = ref<DispatchJob[]>([]);
const loading = ref(false);

async function load() {
	loading.value = true;
	try {
		dispatchJobs.value = await dispatchJobsApi.list(buildParams());
	} catch (error) {
		console.error("Failed to load dispatch jobs:", error);
	} finally {
		loading.value = false;
	}
}

// Filter options
const applicationOptions = ref<FilterOption[]>([]);
const subdomainOptions = ref<FilterOption[]>([]);
const aggregateOptions = ref<FilterOption[]>([]);
const codeOptions = ref<FilterOption[]>([]);
const statusOptions = ref<FilterOption[]>([]);

onMounted(async () => {
	await loadFilterOptions();
	await load();
});

async function loadFilterOptions() {
	try {
		const data = await dispatchJobsApi.filterOptions();
		applicationOptions.value = data.applications || [];
		subdomainOptions.value = data.subdomains || [];
		aggregateOptions.value = data.aggregates || [];
		codeOptions.value = data.codes || [];
		statusOptions.value = data.statuses || [];
	} catch (error) {
		console.error("Failed to load filter options:", error);
	}
}

// Cascading clears: changing a parent wipes its dependent children. The
// child writes are wrapped in `withSuppressed` so useListState's per-ref
// watchers don't each spam syncToUrl; we sync once at the end.
function onClientsChange() {
	withSuppressed(() => {
		filters.applications.value = [];
		filters.subdomains.value = [];
		filters.aggregates.value = [];
		filters.codes.value = [];
	});
	syncToUrl();
	load();
}

function onApplicationsChange() {
	withSuppressed(() => {
		filters.subdomains.value = [];
		filters.aggregates.value = [];
		filters.codes.value = [];
	});
	syncToUrl();
	load();
}

function onSubdomainsChange() {
	withSuppressed(() => {
		filters.aggregates.value = [];
		filters.codes.value = [];
	});
	syncToUrl();
	load();
}

function onAggregatesChange() {
	withSuppressed(() => {
		filters.codes.value = [];
	});
	syncToUrl();
	load();
}

function getSeverity(
	status: string,
):
	| "success"
	| "info"
	| "warn"
	| "danger"
	| "secondary"
	| "contrast"
	| undefined {
	switch (status) {
		case "COMPLETED":
			return "success";
		case "PENDING":
			return "info";
		case "QUEUED":
			return "info";
		case "PROCESSING":
			return "warn";
		case "FAILED":
			return "danger";
		case "CANCELLED":
			return "secondary";
		case "EXPIRED":
			return "secondary";
		default:
			return "secondary";
	}
}

function getModeSeverity(
	mode: string,
):
	| "success"
	| "info"
	| "warn"
	| "danger"
	| "secondary"
	| "contrast"
	| undefined {
	switch (mode) {
		case "IMMEDIATE":
			return "success";
		case "NEXT_ON_ERROR":
			return "warn";
		case "BLOCK_ON_ERROR":
			return "danger";
		default:
			return "secondary";
	}
}

function formatDate(dateStr: string | undefined): string {
	if (!dateStr) return "-";
	return new Date(dateStr).toLocaleString();
}

function formatAttempts(job: DispatchJob): string {
	return `${job.attemptCount || 0}/${(job["maxRetries"] as number | undefined) || 3}`;
}

function formatCode(code: string | undefined): {
	app?: string;
	subdomain?: string;
	aggregate?: string;
	event?: string;
} {
	if (!code) return {};
	const parts = code.split(":");
	return {
		app: parts[0],
		subdomain: parts[1],
		aggregate: parts[2],
		event: parts[3],
	};
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Dispatch Jobs</h1>
        <p class="page-subtitle">Monitor webhook dispatch jobs and delivery status</p>
      </div>
    </header>

    <div class="fc-card">
      <div class="toolbar">
        <div class="filter-row">
          <ClientFilter
            v-model="filters.clients.value"
            class="filter-select"
            @change="onClientsChange"
          />
          <MultiSelect
            v-model="filters.applications.value"
            :options="applicationOptions"
            optionLabel="label"
            optionValue="value"
            placeholder="All Applications"
            class="filter-select"
            @change="onApplicationsChange"
          />
          <MultiSelect
            v-model="filters.subdomains.value"
            :options="subdomainOptions"
            optionLabel="label"
            optionValue="value"
            placeholder="All Subdomains"
            class="filter-select"
            @change="onSubdomainsChange"
          />
          <MultiSelect
            v-model="filters.aggregates.value"
            :options="aggregateOptions"
            optionLabel="label"
            optionValue="value"
            placeholder="All Aggregates"
            class="filter-select"
            @change="onAggregatesChange"
          />
          <MultiSelect
            v-model="filters.codes.value"
            :options="codeOptions"
            optionLabel="label"
            optionValue="value"
            placeholder="All Codes"
            class="filter-select"
            @change="load"
          />
        </div>
        <div class="filter-row">
          <MultiSelect
            v-model="filters.statuses.value"
            :options="statusOptions"
            optionLabel="label"
            optionValue="value"
            placeholder="All Statuses"
            class="filter-select"
            @change="load"
          />
          <IconField>
            <InputIcon class="pi pi-search" />
            <InputText
              v-model="filters.search.value"
              placeholder="Search by source..."
              @keyup.enter="load"
            />
          </IconField>
          <Select
            v-model="pageSize"
            :options="sizeOptions"
            class="size-select"
            @change="load"
            v-tooltip="'Result size — most recent N jobs'"
          />
          <Button
            v-if="hasActiveFilters"
            icon="pi pi-filter-slash"
            text
            rounded
            @click="() => { clearFilters(); load(); }"
            v-tooltip="'Clear filters'"
          />
          <Button
            icon="pi pi-refresh"
            text
            rounded
            @click="load"
            v-tooltip="'Refresh'"
          />
        </div>
      </div>

      <DataTable
        :value="dispatchJobs"
        :loading="loading"
        stripedRows
        emptyMessage="No dispatch jobs found"
        tableStyle="min-width: 60rem"
      >
        <Column field="id" header="Job ID" style="width: 10rem">
          <template #body="{ data }">
            <span class="font-mono text-sm">{{ data.id?.slice(0, 8) }}...</span>
          </template>
        </Column>
        <Column field="code" header="Code">
          <template #body="{ data }">
            <span class="code-display">
              <span class="code-segment app">{{ formatCode(data.code).app }}</span>
              <span class="code-separator">:</span>
              <span class="code-segment subdomain">{{ formatCode(data.code).subdomain }}</span>
              <span class="code-separator">:</span>
              <span class="code-segment aggregate">{{ formatCode(data.code).aggregate }}</span>
              <span class="code-separator">:</span>
              <span class="code-segment event">{{ formatCode(data.code).event }}</span>
            </span>
          </template>
        </Column>
        <Column field="source" header="Source" />
        <Column field="status" header="Status" style="width: 8rem">
          <template #body="{ data }">
            <Tag :value="data.status" :severity="getSeverity(data.status)" />
          </template>
        </Column>
        <Column field="mode" header="Mode" style="width: 8rem">
          <template #body="{ data }">
            <Tag :value="data.mode || 'IMMEDIATE'" :severity="getModeSeverity(data.mode)" />
          </template>
        </Column>
        <Column header="Attempts" style="width: 6rem">
          <template #body="{ data }">
            {{ formatAttempts(data) }}
          </template>
        </Column>
        <Column field="targetUrl" header="Target URL">
          <template #body="{ data }">
            <span class="text-sm truncate" style="max-width: 200px; display: inline-block">
              {{ data.targetUrl }}
            </span>
          </template>
        </Column>
        <Column field="createdAt" header="Created" style="width: 10rem">
          <template #body="{ data }">
            <span class="text-sm">{{ formatDate(data.createdAt) }}</span>
          </template>
        </Column>
        <Column header="Actions" style="width: 8rem">
          <template #body="{ data }">
            <div class="action-buttons">
              <Button icon="pi pi-eye" text rounded size="small" v-tooltip="'View details'" />
              <Button
                icon="pi pi-replay"
                text
                rounded
                size="small"
                v-tooltip="'Retry'"
                :disabled="data.status === 'COMPLETED' || data.status === 'PROCESSING'"
              />
            </div>
          </template>
        </Column>
      </DataTable>

      <!-- No pagination — dispatch jobs ingest at high rates and "page 2"
           is meaningless. Adjust size or narrow filters to see more. -->
      <div class="result-summary">
        Showing the {{ dispatchJobs.length }} most recent dispatch jobs
        <span v-if="dispatchJobs.length === pageSize"> (size limit reached — narrow filters or increase size)</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.size-select {
  width: 6rem;
}

.result-summary {
  text-align: center;
  font-size: 0.8125rem;
  color: var(--text-color-secondary);
  padding: 0.75rem 0 0.25rem;
}

.toolbar {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  margin-bottom: 16px;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.filter-select {
  min-width: 160px;
}

.font-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.text-sm {
  font-size: 0.875rem;
}

.truncate {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.action-buttons {
  display: flex;
  gap: 0.25rem;
  align-items: center;
}
</style>
