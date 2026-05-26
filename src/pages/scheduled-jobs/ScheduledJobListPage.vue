<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { useListState } from "@/composables/useListState";
import { useReturnTo } from "@/composables/useReturnTo";
import {
	scheduledJobsApi,
	type ScheduledJob,
	type ScheduledJobsFilterOptions,
} from "@/api/scheduled-jobs";

const router = useRouter();
const { navigateToDetail } = useReturnTo();

const jobs = ref<ScheduledJob[]>([]);
const total = ref(0);
const loading = ref(false);

const { filters, page, pageSize, hasActiveFilters, clearFilters, onPage } =
	useListState(
		{
			filters: {
				clientId: { type: "string", key: "clientId" },
				status: { type: "string", key: "status" },
				search: { type: "string", key: "q" },
			},
			pageSize: 20,
			sortField: "createdAt",
			sortOrder: "desc",
		},
		() => load(),
	);

const filterOptions = ref<ScheduledJobsFilterOptions>({
	clients: [],
	statuses: [],
});

async function loadFilterOptions() {
	try {
		filterOptions.value = await scheduledJobsApi.filterOptions();
	} catch (err) {
		console.error("Failed to load filter options", err);
	}
}

async function load() {
	loading.value = true;
	try {
		const result = await scheduledJobsApi.list({
			clientId: filters.clientId.value || undefined,
			status: filters.status.value || undefined,
			search: filters.search.value || undefined,
			page: page.value,
			size: pageSize.value,
		});
		jobs.value = result.data;
		total.value = result.total;
	} catch (err) {
		console.error("Failed to load scheduled jobs", err);
	} finally {
		loading.value = false;
	}
}

onMounted(async () => {
	await loadFilterOptions();
	await load();
});

function viewJob(job: ScheduledJob) {
	navigateToDetail(`/scheduled-jobs/${job.id}`);
}

function onRowClick(event: { data: ScheduledJob }) {
	viewJob(event.data);
}

function statusSeverity(status: string): "success" | "warn" | "secondary" | "info" {
	switch (status) {
		case "ACTIVE":
			return "success";
		case "PAUSED":
			return "warn";
		case "ARCHIVED":
			return "secondary";
		default:
			return "info";
	}
}

function formatCrons(crons: string[]): string {
	if (crons.length === 0) return "—";
	if (crons.length === 1) return crons[0] ?? "";
	return `${crons[0]} (+${crons.length - 1})`;
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
        <h1 class="page-title">Scheduled Jobs</h1>
        <p class="page-subtitle">Cron-triggered webhook jobs</p>
      </div>
      <Button
        label="New Scheduled Job"
        icon="pi pi-plus"
        @click="router.push('/scheduled-jobs/create')"
      />
    </header>

    <div class="fc-card">
      <div class="toolbar">
        <div class="filter-row">
          <Select
            v-model="filters.clientId.value"
            :options="filterOptions.clients"
            optionLabel="label"
            optionValue="value"
            placeholder="All clients"
            class="filter-select"
            showClear
          />
          <Select
            v-model="filters.status.value"
            :options="filterOptions.statuses"
            optionLabel="label"
            optionValue="value"
            placeholder="All statuses"
            class="filter-select"
            showClear
          />
          <IconField class="search-field">
            <InputIcon class="pi pi-search" />
            <InputText
              v-model="filters.search.value"
              placeholder="Code or name…"
            />
          </IconField>
          <Button
            v-if="hasActiveFilters"
            icon="pi pi-filter-slash"
            text
            rounded
            severity="secondary"
            v-tooltip="'Clear filters'"
            @click="clearFilters"
          />
        </div>
      </div>

      <DataTable
        :value="jobs"
        :loading="loading"
        :total-records="total"
        :rows="pageSize"
        :first="page * pageSize"
        lazy
        paginator
        :rows-per-page-options="[10, 20, 50, 100]"
        data-key="id"
        row-hover
        selection-mode="single"
        stripedRows
        emptyMessage="No scheduled jobs found"
        @row-click="onRowClick"
        @page="onPage"
      >
        <Column header="Code" field="code" style="width: 22%">
          <template #body="{ data }">
            <span class="font-mono text-sm">{{ data.code }}</span>
            <div v-if="data.hasActiveInstance" class="active-flag">
              <i class="pi pi-spinner pi-spin" /> running
            </div>
          </template>
        </Column>
        <Column header="Name" field="name" style="width: 18%" />
        <Column header="Scope" style="width: 14%">
          <template #body="{ data }">
            <span v-if="data.clientName">{{ data.clientName }}</span>
            <span v-else class="scope-platform">Platform</span>
          </template>
        </Column>
        <Column header="Crons" style="width: 18%">
          <template #body="{ data }">
            <span class="font-mono text-sm">{{ formatCrons(data.crons) }}</span>
            <div class="text-muted text-xs">{{ data.timezone }}</div>
          </template>
        </Column>
        <Column header="Status" style="width: 8rem">
          <template #body="{ data }">
            <Tag :value="data.status" :severity="statusSeverity(data.status)" />
          </template>
        </Column>
        <Column header="Last Fired" style="width: 14%">
          <template #body="{ data }">
            <span class="text-sm">{{ formatDate(data.lastFiredAt) }}</span>
          </template>
        </Column>
        <Column header="" style="width: 4rem">
          <template #body="{ data }">
            <Button
              icon="pi pi-arrow-right"
              severity="secondary"
              text
              rounded
              @click.stop="viewJob(data)"
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
  min-width: 200px;
}

.search-field {
  flex: 1 1 240px;
}

.search-field :deep(.p-inputtext) {
  width: 100%;
}

.font-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.text-sm { font-size: 0.875rem; }
.text-xs { font-size: 0.75rem; }
.text-muted { color: var(--text-color-secondary); }

.active-flag {
  font-size: 0.75rem;
  color: var(--orange-500, #f97316);
  margin-top: 0.25rem;
  display: flex;
  align-items: center;
  gap: 0.25rem;
}

.scope-platform {
  color: var(--text-color-secondary);
  font-style: italic;
}
</style>
