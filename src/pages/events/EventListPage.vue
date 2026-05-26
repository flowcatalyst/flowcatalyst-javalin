<script setup lang="ts">
import { ref, onMounted } from "vue";
import { useListState } from "@/composables/useListState";
import ClientFilter from "@/components/ClientFilter.vue";
import {
	eventsApi,
	type EventRead,
	type EventDetail,
	type EventsListParams,
} from "@/api/events";

interface FilterOption {
	value: string;
	label: string;
}

const sizeOptions = [50, 100, 200, 500, 1000];

const { filters, pageSize, hasActiveFilters, clearFilters, syncToUrl, withSuppressed } =
	useListState({
		filters: {
			clients: { type: "array", key: "clients" },
			applications: { type: "array", key: "applications" },
			subdomains: { type: "array", key: "subdomains" },
			aggregates: { type: "array", key: "aggregates" },
			types: { type: "array", key: "types" },
			search: { type: "string", key: "q" },
		},
		pageSize: 200,
	});

function buildParams(): EventsListParams {
	return {
		size: pageSize.value,
		clientIds: filters.clients.value.length ? filters.clients.value : undefined,
		applications: filters.applications.value.length ? filters.applications.value : undefined,
		subdomains: filters.subdomains.value.length ? filters.subdomains.value : undefined,
		aggregates: filters.aggregates.value.length ? filters.aggregates.value : undefined,
		types: filters.types.value.length ? filters.types.value : undefined,
		source: filters.search.value || undefined,
	};
}

const events = ref<EventRead[]>([]);
const loading = ref(false);

async function load() {
	loading.value = true;
	try {
		events.value = await eventsApi.list(buildParams());
	} catch (error) {
		console.error("Failed to load events:", error);
	} finally {
		loading.value = false;
	}
}

// Filter options (from server)
const applicationOptions = ref<FilterOption[]>([]);
const subdomainOptions = ref<FilterOption[]>([]);
const aggregateOptions = ref<FilterOption[]>([]);
const typeOptions = ref<FilterOption[]>([]);
const loadingOptions = ref(false);

// Detail dialog
const selectedEvent = ref<(EventRead & Partial<EventDetail>) | null>(null);
const showDetailDialog = ref(false);
const loadingDetail = ref(false);

onMounted(async () => {
	await loadFilterOptions();
	await load();
});

// Cascading clears: changing a parent wipes its dependent children. The
// child writes are wrapped in `withSuppressed` so useListState's per-ref
// watchers don't each spam syncToUrl; we sync once at the end.
function onClientsChange() {
	withSuppressed(() => {
		filters.applications.value = [];
		filters.subdomains.value = [];
		filters.aggregates.value = [];
		filters.types.value = [];
	});
	syncToUrl();
	load();
}

function onApplicationsChange() {
	withSuppressed(() => {
		filters.subdomains.value = [];
		filters.aggregates.value = [];
		filters.types.value = [];
	});
	syncToUrl();
	load();
}

function onSubdomainsChange() {
	withSuppressed(() => {
		filters.aggregates.value = [];
		filters.types.value = [];
	});
	syncToUrl();
	load();
}

function onAggregatesChange() {
	withSuppressed(() => {
		filters.types.value = [];
	});
	syncToUrl();
	load();
}

async function loadFilterOptions() {
	loadingOptions.value = true;
	try {
		const data = await eventsApi.filterOptions();
		applicationOptions.value = data.applications || [];
		subdomainOptions.value = data.subdomains || [];
		// `aggregates` is not surfaced by the shared filter-options endpoint.
		aggregateOptions.value = [];
		typeOptions.value = data.eventTypes || [];
	} catch (error) {
		console.error("Failed to load filter options:", error);
	} finally {
		loadingOptions.value = false;
	}
}

function clearAllFilters() {
	clearFilters();
	load();
}

async function viewEventDetail(event: EventRead) {
	loadingDetail.value = true;
	showDetailDialog.value = true;
	selectedEvent.value = { ...event };
	try {
		const detail = await eventsApi.get(event.id);
		selectedEvent.value = { ...event, ...detail };
	} catch (error) {
		console.error("Failed to load event details:", error);
	} finally {
		loadingDetail.value = false;
	}
}

function formatDate(dateStr: string | undefined): string {
	if (!dateStr) return "-";
	return new Date(dateStr).toLocaleString();
}

function formatData(data: unknown): string {
	if (data == null) return "-";
	if (typeof data === "object") {
		try {
			return JSON.stringify(data, null, 2);
		} catch {
			return String(data);
		}
	}
	if (typeof data !== "string") return String(data);
	try {
		return JSON.stringify(JSON.parse(data), null, 2);
	} catch {
		return data;
	}
}

function truncateId(id: string | undefined): string {
	if (!id) return "-";
	return id.length > 10 ? `${id.slice(0, 10)}...` : id;
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Events</h1>
        <p class="page-subtitle">Browse events from the event store</p>
      </div>
    </header>

    <div class="fc-card">
      <!-- Cascading Filter Bar -->
      <div class="filter-bar">
        <div class="filter-row">
          <div class="filter-group">
            <label>Client</label>
            <ClientFilter
              v-model="filters.clients.value"
              class="filter-select"
              @change="onClientsChange"
            />
          </div>
          <div class="filter-group">
            <label>Application</label>
            <MultiSelect
              v-model="filters.applications.value"
              :options="applicationOptions"
              optionLabel="label"
              optionValue="value"
              placeholder="All Applications"
              :maxSelectedLabels="2"
              :loading="loadingOptions"
              class="filter-select"
              filter
              @change="onApplicationsChange"
            />
          </div>
          <div class="filter-group">
            <label>Subdomain</label>
            <MultiSelect
              v-model="filters.subdomains.value"
              :options="subdomainOptions"
              optionLabel="label"
              optionValue="value"
              placeholder="All Subdomains"
              :maxSelectedLabels="2"
              :loading="loadingOptions"
              class="filter-select"
              filter
              @change="onSubdomainsChange"
            />
          </div>
          <div class="filter-group">
            <label>Aggregate</label>
            <MultiSelect
              v-model="filters.aggregates.value"
              :options="aggregateOptions"
              optionLabel="label"
              optionValue="value"
              placeholder="All Aggregates"
              :maxSelectedLabels="2"
              :loading="loadingOptions"
              class="filter-select"
              filter
              @change="onAggregatesChange"
            />
          </div>
          <div class="filter-group">
            <label>Event Type</label>
            <MultiSelect
              v-model="filters.types.value"
              :options="typeOptions"
              optionLabel="label"
              optionValue="value"
              placeholder="All Types"
              :maxSelectedLabels="1"
              :loading="loadingOptions"
              class="filter-select filter-select-wide"
              filter
              @change="load"
            />
          </div>
        </div>
        <div class="filter-actions">
          <IconField>
            <InputIcon class="pi pi-search" />
            <InputText
              v-model="filters.search.value"
              placeholder="Search by source..."
              @keyup.enter="load"
              class="search-input"
            />
          </IconField>
          <Button
            icon="pi pi-filter-slash"
            text
            rounded
            @click="clearAllFilters"
            v-tooltip="'Clear all filters'"
            :disabled="!hasActiveFilters"
          />
          <Select
            v-model="pageSize"
            :options="sizeOptions"
            class="size-select"
            @change="load"
            v-tooltip="'Result size — most recent N events'"
          />
          <Button icon="pi pi-refresh" text rounded @click="load" v-tooltip="'Refresh'" />
        </div>
      </div>

      <DataTable
        :value="events"
        :loading="loading"
        stripedRows
        emptyMessage="No events found"
        tableStyle="min-width: 60rem"
      >
        <Column field="id" header="Event ID" style="width: 10rem">
          <template #body="{ data }">
            <span class="font-mono text-sm">{{ truncateId(data.id) }}</span>
          </template>
        </Column>
        <Column field="type" header="Type">
          <template #body="{ data }">
            <Tag :value="data.type" severity="info" />
          </template>
        </Column>
        <Column field="source" header="Source" />
        <Column field="subject" header="Subject">
          <template #body="{ data }">
            <span class="text-sm truncate-cell">{{ data.subject || '-' }}</span>
          </template>
        </Column>
        <Column field="clientId" header="Client" style="width: 10rem">
          <template #body="{ data }">
            <span v-if="data.clientId" class="font-mono text-sm">{{
              truncateId(data.clientId)
            }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </Column>
        <Column field="time" header="Time" style="width: 12rem">
          <template #body="{ data }">
            <span class="text-sm">{{ formatDate(data.time) }}</span>
          </template>
        </Column>
        <Column header="Actions" style="width: 6rem">
          <template #body="{ data }">
            <Button
              icon="pi pi-eye"
              text
              rounded
              v-tooltip="'View details'"
              @click="viewEventDetail(data)"
            />
          </template>
        </Column>
      </DataTable>

      <!-- No pagination — events ingest at high rates and "page 2" is
           meaningless. Adjust size or narrow filters to see more. -->
      <div class="result-summary">
        Showing the {{ events.length }} most recent events
        <span v-if="events.length === pageSize"> (size limit reached — narrow filters or increase size)</span>
      </div>
    </div>

    <!-- Event Detail Dialog -->
    <Dialog
      v-model:visible="showDetailDialog"
      header="Event Details"
      :style="{ width: '700px' }"
      modal
    >
      <div v-if="loadingDetail" class="flex justify-center p-4">
        <i class="pi pi-spin pi-spinner" style="font-size: 2rem"></i>
      </div>
      <div v-else-if="selectedEvent" class="event-detail">
        <div class="detail-row">
          <label>ID</label>
          <span class="font-mono">{{ selectedEvent.id }}</span>
        </div>
        <div class="detail-row">
          <label>Type</label>
          <Tag :value="selectedEvent.type" severity="info" />
        </div>
        <div class="detail-row">
          <label>Application</label>
          <span>{{ selectedEvent.application || '-' }}</span>
        </div>
        <div class="detail-row">
          <label>Subdomain</label>
          <span>{{ selectedEvent.subdomain || '-' }}</span>
        </div>
        <div class="detail-row">
          <label>Aggregate</label>
          <span>{{ selectedEvent.aggregate || '-' }}</span>
        </div>
        <div class="detail-row">
          <label>Source</label>
          <span>{{ selectedEvent.source }}</span>
        </div>
        <div class="detail-row">
          <label>Subject</label>
          <span>{{ selectedEvent.subject || '-' }}</span>
        </div>
        <div class="detail-row">
          <label>Time</label>
          <span>{{ formatDate(selectedEvent.time) }}</span>
        </div>
        <div class="detail-row">
          <label>Client ID</label>
          <span v-if="selectedEvent.clientId" class="font-mono">{{ selectedEvent.clientId }}</span>
          <span v-else class="text-muted">-</span>
        </div>
        <div class="detail-row">
          <label>Message Group</label>
          <span>{{ selectedEvent.messageGroup || '-' }}</span>
        </div>
        <div class="detail-row">
          <label>Correlation ID</label>
          <span class="font-mono">{{ selectedEvent.correlationId || '-' }}</span>
        </div>
        <div class="detail-row">
          <label>Causation ID</label>
          <span class="font-mono">{{ selectedEvent.causationId || '-' }}</span>
        </div>
        <div class="detail-row">
          <label>Deduplication ID</label>
          <span class="font-mono">{{ selectedEvent.deduplicationId || '-' }}</span>
        </div>
        <div class="detail-row">
          <label>Projected At</label>
          <span>{{ formatDate(selectedEvent.projectedAt) }}</span>
        </div>
        <div class="detail-section">
          <label>Data</label>
          <pre class="data-block">{{ formatData(selectedEvent.data) }}</pre>
        </div>
        <div v-if="selectedEvent.contextData?.length" class="detail-section">
          <label>Context Data</label>
          <div class="context-data">
            <div v-for="cd in selectedEvent.contextData" :key="cd.key" class="context-item">
              <span class="context-key">{{ cd.key }}:</span>
              <span class="context-value">{{ cd.value }}</span>
            </div>
          </div>
        </div>
      </div>
    </Dialog>
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  margin-bottom: 1rem;
  padding: 0.75rem;
  background: var(--surface-ground);
  border-radius: 6px;
  border: 1px solid var(--surface-border);
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
}

.filter-group {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  min-width: 150px;
}

.filter-group label {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--text-color-secondary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.filter-select {
  width: 180px;
}

.filter-select-wide {
  width: 280px;
}

.filter-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding-top: 0.5rem;
  border-top: 1px solid var(--surface-border);
}

.search-input {
  width: 250px;
}

.size-select {
  width: 6rem;
}

.result-summary {
  text-align: center;
  font-size: 0.8125rem;
  color: var(--text-color-secondary);
  padding: 0.75rem 0 0.25rem;
}

.font-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.text-sm {
  font-size: 0.875rem;
}

.text-muted {
  color: var(--text-color-secondary);
}

.truncate-cell {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
}

.event-detail {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.detail-row {
  display: flex;
  gap: 1rem;
}

.detail-row label {
  font-weight: 600;
  min-width: 120px;
  color: var(--text-color-secondary);
}

.detail-section {
  margin-top: 0.5rem;
}

.detail-section label {
  display: block;
  font-weight: 600;
  margin-bottom: 0.5rem;
  color: var(--text-color-secondary);
}

.data-block {
  background: var(--surface-ground);
  border: 1px solid var(--surface-border);
  border-radius: 6px;
  padding: 1rem;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 0.875rem;
  overflow-x: auto;
  max-height: 300px;
  white-space: pre-wrap;
  word-break: break-word;
}

.context-data {
  background: var(--surface-ground);
  border: 1px solid var(--surface-border);
  border-radius: 6px;
  padding: 0.75rem;
}

.context-item {
  padding: 0.25rem 0;
}

.context-key {
  font-weight: 500;
  margin-right: 0.5rem;
}

.context-value {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.flex {
  display: flex;
}

.justify-center {
  justify-content: center;
}

.p-4 {
  padding: 1rem;
}
</style>
