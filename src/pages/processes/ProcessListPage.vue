<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import { useReturnTo } from "@/composables/useReturnTo";
import { processesApi } from "@/api/processes";
import type { Process, ProcessStatus } from "@/api/processes";

const router = useRouter();
const { navigateToDetail } = useReturnTo();

const processes = ref<Process[]>([]);
const loading = ref(true);

const selectedApplication = ref<string | null>(null);
const selectedSubdomain = ref<string | null>(null);
const selectedStatus = ref<ProcessStatus | null>(null);
const search = ref("");

const applicationOptions = computed(() => {
	const set = new Set(processes.value.map((p) => p.application));
	return Array.from(set).sort().map((v) => ({ label: v, value: v }));
});
const subdomainOptions = computed(() => {
	const set = new Set(
		processes.value
			.filter((p) => !selectedApplication.value || p.application === selectedApplication.value)
			.map((p) => p.subdomain),
	);
	return Array.from(set).sort().map((v) => ({ label: v, value: v }));
});
const statusOptions = [
	{ label: "Current", value: "CURRENT" },
	{ label: "Archived", value: "ARCHIVED" },
];

const hasActiveFilters = computed(
	() =>
		!!selectedApplication.value ||
		!!selectedSubdomain.value ||
		!!selectedStatus.value ||
		!!search.value,
);

const filtered = computed(() => {
	return processes.value.filter((p) => {
		if (selectedApplication.value && p.application !== selectedApplication.value) return false;
		if (selectedSubdomain.value && p.subdomain !== selectedSubdomain.value) return false;
		if (selectedStatus.value && p.status !== selectedStatus.value) return false;
		if (search.value) {
			const term = search.value.toLowerCase();
			if (!p.code.toLowerCase().includes(term) && !p.name.toLowerCase().includes(term)) {
				return false;
			}
		}
		return true;
	});
});

async function load() {
	loading.value = true;
	try {
		// Pull both CURRENT and ARCHIVED so the status filter can switch
		// without re-fetching. Volume is low — process docs are hand-curated.
		const res = await processesApi.list({});
		processes.value = res.items;
	} catch (e) {
		toast.error("Failed to load", (e as Error).message);
	} finally {
		loading.value = false;
	}
}

onMounted(() => load());

function viewProcess(p: Process) {
	navigateToDetail(`/processes/${p.id}`);
}

function clearFilters() {
	selectedApplication.value = null;
	selectedSubdomain.value = null;
	selectedStatus.value = null;
	search.value = "";
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Processes</h1>
        <p class="page-subtitle">
          Workflow documentation — how events, dispatch jobs, and reactive aggregates compose into a business process.
        </p>
      </div>
      <div class="header-actions">
        <Button
          label="Create Process"
          icon="pi pi-plus"
          @click="router.push('/processes/create')"
        />
      </div>
    </header>

    <div class="fc-card filter-card">
      <div class="filter-row">
        <div class="filter-group">
          <label>Application</label>
          <Select
            v-model="selectedApplication"
            :options="applicationOptions"
            optionLabel="label"
            optionValue="value"
            placeholder="All applications"
            :showClear="true"
            class="filter-select"
          />
        </div>
        <div class="filter-group">
          <label>Subdomain</label>
          <Select
            v-model="selectedSubdomain"
            :options="subdomainOptions"
            optionLabel="label"
            optionValue="value"
            placeholder="All subdomains"
            :showClear="true"
            class="filter-select"
          />
        </div>
        <div class="filter-group">
          <label>Status</label>
          <Select
            v-model="selectedStatus"
            :options="statusOptions"
            optionLabel="label"
            optionValue="value"
            placeholder="All statuses"
            :showClear="true"
            class="filter-select"
          />
        </div>
        <div class="filter-group search-group">
          <label>Search</label>
          <IconField iconPosition="left">
            <InputIcon class="pi pi-search" />
            <InputText v-model="search" placeholder="Code or name" />
          </IconField>
        </div>
        <div class="filter-actions">
          <Button
            v-if="hasActiveFilters"
            label="Clear filters"
            icon="pi pi-filter-slash"
            text
            severity="secondary"
            @click="clearFilters"
          />
        </div>
      </div>
    </div>

    <div class="fc-card table-card">
      <DataTable
        :value="filtered"
        :loading="loading"
        :paginator="true"
        :rows="50"
        :rowsPerPageOptions="[25, 50, 100, 250]"
        :showCurrentPageReport="true"
        currentPageReportTemplate="Showing {first} to {last} of {totalRecords} processes"
        size="small"
        @row-click="(e) => viewProcess(e.data)"
        :rowClass="() => 'clickable-row'"
      >
        <Column header="Code" style="width: 30%">
          <template #body="{ data }">
            <div class="code-display">
              <span class="code-segment app">{{ data.application }}</span>
              <span class="code-separator">:</span>
              <span class="code-segment subdomain">{{ data.subdomain }}</span>
              <span class="code-separator">:</span>
              <span class="code-segment name">{{ data.processName }}</span>
            </div>
          </template>
        </Column>

        <Column field="name" header="Name" style="width: 22%">
          <template #body="{ data }">
            <span class="name-text">{{ data.name }}</span>
          </template>
        </Column>

        <Column field="description" header="Description" style="width: 28%">
          <template #body="{ data }">
            <span class="description-text" v-tooltip.top="data.description">
              {{ data.description || '—' }}
            </span>
          </template>
        </Column>

        <Column header="Tags" style="width: 12%">
          <template #body="{ data }">
            <div class="tag-list">
              <Tag
                v-for="t in data.tags"
                :key="t"
                :value="t"
                severity="secondary"
              />
              <span v-if="data.tags.length === 0" class="muted">—</span>
            </div>
          </template>
        </Column>

        <Column header="Status" style="width: 8%">
          <template #body="{ data }">
            <Tag
              :value="data.status"
              :severity="data.status === 'CURRENT' ? 'success' : 'secondary'"
            />
          </template>
        </Column>

        <template #empty>
          <div class="empty-message">
            <i class="pi pi-inbox"></i>
            <span>No processes yet</span>
            <Button label="Create your first process" link @click="router.push('/processes/create')" />
          </div>
        </template>
      </DataTable>
    </div>
  </div>
</template>

<style scoped>
.header-actions {
  display: flex;
  gap: 8px;
}

.filter-card {
  margin-bottom: 24px;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: flex-end;
}

.filter-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 180px;
}

.filter-group label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-color-secondary);
}

.filter-select {
  min-width: 180px;
}

.search-group {
  min-width: 220px;
}

.filter-actions {
  margin-left: auto;
}

.table-card {
  padding: 0;
  overflow: hidden;
}

.code-display {
  display: inline-flex;
  align-items: center;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
}

.code-segment.app { color: var(--p-primary-color); font-weight: 600; }
.code-segment.subdomain { color: var(--text-color); }
.code-segment.name { color: var(--text-color); font-weight: 500; }
.code-separator { color: var(--text-color-secondary); margin: 0 2px; }

.name-text { font-weight: 500; }
.description-text {
  color: var(--text-color-secondary);
  font-size: 13px;
  display: block;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-list { display: flex; flex-wrap: wrap; gap: 4px; }
.muted { color: var(--text-color-secondary); font-size: 12px; }

.empty-message {
  text-align: center;
  padding: 48px 24px;
  color: var(--text-color-secondary);
}
.empty-message i {
  font-size: 48px;
  display: block;
  margin-bottom: 16px;
  color: var(--surface-border);
}
.empty-message span {
  display: block;
  margin-bottom: 12px;
}

:deep(.clickable-row) {
  cursor: pointer;
  transition: background-color 0.15s;
}
</style>
