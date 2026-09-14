<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import {
  developerApi,
  type DeveloperEventTypeSummary,
  type DeveloperSpecVersionSummary,
} from "@/api/developer";
import SchemaViewerInline from "./SchemaViewerInline.vue";

const props = defineProps<{ applicationId: string }>();

const eventTypes = ref<DeveloperEventTypeSummary[]>([]);
const loading = ref(true);
const selectedId = ref<string | null>(null);

// Filters (status defaults to CURRENT, matching the regular Event Types page).
const subdomain = ref<string | null>(null);
const aggregate = ref<string | null>(null);
const status = ref<string | null>("CURRENT");

async function load() {
  loading.value = true;
  try {
    const res = await developerApi.listEventTypes(props.applicationId);
    eventTypes.value = res.items;
    // Default-select the first row that matches the active filters.
    selectedId.value = filtered.value[0]?.id ?? null;
  } finally {
    loading.value = false;
  }
}

onMounted(load);
watch(
  () => props.applicationId,
  () => {
    selectedId.value = null;
    load();
  },
);

const subdomainOptions = computed(() => {
  const set = new Set<string>();
  for (const et of eventTypes.value) if (et.subdomain) set.add(et.subdomain);
  return [...set].sort().map((v) => ({ label: v, value: v }));
});

const aggregateOptions = computed(() => {
  const set = new Set<string>();
  for (const et of eventTypes.value) if (et.aggregate) set.add(et.aggregate);
  return [...set].sort().map((v) => ({ label: v, value: v }));
});

const statusOptions = [
  { label: "Current", value: "CURRENT" },
  { label: "Archived", value: "ARCHIVED" },
];

const filtered = computed(() => {
  return eventTypes.value.filter((et) => {
    if (subdomain.value && et.subdomain !== subdomain.value) return false;
    if (aggregate.value && et.aggregate !== aggregate.value) return false;
    if (status.value && et.status !== status.value) return false;
    return true;
  });
});

const hasActiveFilters = computed(
  () => !!subdomain.value || !!aggregate.value || status.value !== "CURRENT",
);

function clearFilters() {
  subdomain.value = null;
  aggregate.value = null;
  status.value = "CURRENT";
}

// Re-select if filters drop the current selection.
watch(filtered, (list) => {
  if (!selectedId.value || !list.find((et) => et.id === selectedId.value)) {
    selectedId.value = list[0]?.id ?? null;
  }
});

const selectedEventType = computed(
  () => eventTypes.value.find((et) => et.id === selectedId.value) ?? null,
);

// Prefer CURRENT, then FINALISING, then any.
const selectedSpecVersion = computed<DeveloperSpecVersionSummary | null>(() => {
  const et = selectedEventType.value;
  if (!et || et.specVersions.length === 0) return null;
  return (
    et.specVersions.find((s) => s.status === "CURRENT") ??
    et.specVersions.find((s) => s.status === "FINALISING") ??
    et.specVersions[0] ??
    null
  );
});

function getStatusSeverity(s: string) {
  return s === "CURRENT" ? "success" : "secondary";
}

function selectEventType(et: DeveloperEventTypeSummary) {
  selectedId.value = et.id;
}
</script>

<template>
  <div class="event-types-tab">
    <!-- Filter row mirrors the regular Event Types page -->
    <div class="toolbar">
      <div class="filter-row">
        <Select
          v-model="subdomain"
          :options="subdomainOptions"
          option-label="label"
          option-value="value"
          placeholder="Subdomain"
          class="filter-select"
          show-clear
        />
        <Select
          v-model="aggregate"
          :options="aggregateOptions"
          option-label="label"
          option-value="value"
          placeholder="Aggregate"
          class="filter-select"
          show-clear
        />
        <Select
          v-model="status"
          :options="statusOptions"
          option-label="label"
          option-value="value"
          placeholder="Status"
          class="filter-select"
          show-clear
        />
        <Button
          v-if="hasActiveFilters"
          label="Clear filters"
          icon="pi pi-times"
          severity="secondary"
          text
          @click="clearFilters"
        />
      </div>
    </div>

    <!-- Top row: event-type list -->
    <div class="fc-card master-card">
      <DataTable
        :value="filtered"
        :loading="loading"
        size="small"
        scrollable
        scroll-height="320px"
        :rowClass="(row) => (row.id === selectedId ? 'selected-row clickable-row' : 'clickable-row')"
        @row-click="(e) => selectEventType(e.data)"
      >
        <Column header="Code" style="width: 35%">
          <template #body="{ data }">
            <div class="code-display">
              <span class="code-segment app">{{ data.application }}</span>
              <span class="code-separator">:</span>
              <span class="code-segment subdomain">{{ data.subdomain }}</span>
              <span class="code-separator">:</span>
              <span class="code-segment aggregate">{{ data.aggregate }}</span>
              <span class="code-separator">:</span>
              <span class="code-segment event">{{ data.eventName }}</span>
            </div>
          </template>
        </Column>

        <Column field="name" header="Name" style="width: 25%">
          <template #body="{ data }">
            <span class="name-text">{{ data.name }}</span>
          </template>
        </Column>

        <Column field="description" header="Description" style="width: 30%">
          <template #body="{ data }">
            <span class="text-muted">{{ data.description || "—" }}</span>
          </template>
        </Column>

        <Column header="Status" style="width: 10%">
          <template #body="{ data }">
            <Tag :value="data.status" :severity="getStatusSeverity(data.status)" />
          </template>
        </Column>

        <template #empty>
          <div class="empty-message">
            <i class="pi pi-inbox"></i>
            <span v-if="hasActiveFilters">No event types match the current filters.</span>
            <span v-else>No event types defined for this application yet.</span>
            <Button v-if="hasActiveFilters" label="Clear filters" link @click="clearFilters" />
          </div>
        </template>
      </DataTable>
    </div>

    <!-- Bottom row: schema/example/language detail for the selected row -->
    <div class="fc-card detail-card">
      <div v-if="!selectedEventType" class="empty-message">
        <i class="pi pi-arrow-up"></i>
        <span>Select an event type above to see its schema and sample code.</span>
      </div>
      <div v-else-if="!selectedSpecVersion" class="empty-message">
        <i class="pi pi-info-circle"></i>
        <span>No schema published for <strong>{{ selectedEventType.code }}</strong> yet.</span>
      </div>
      <SchemaViewerInline
        v-else
        :spec-version="selectedSpecVersion"
        :event-code="selectedEventType.code"
      />
    </div>
  </div>
</template>

<style scoped>
.event-types-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-top: 16px;
}

.toolbar {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
}

.filter-select {
  min-width: 180px;
}

.master-card {
  padding: 0;
  overflow: hidden;
}

.detail-card {
  padding: 0;
  overflow: hidden;
  min-height: 360px;
  display: flex;
  flex-direction: column;
}

.code-display {
  font-family: ui-monospace, SFMono-Regular, monospace;
  font-size: 13px;
}

.code-segment {
  padding: 2px 4px;
  border-radius: 3px;
}

.code-segment.app {
  color: var(--p-indigo-600, #4f46e5);
}

.code-segment.subdomain {
  color: var(--p-cyan-600, #0891b2);
}

.code-segment.aggregate {
  color: var(--p-emerald-600, #059669);
}

.code-segment.event {
  color: var(--p-amber-600, #d97706);
  font-weight: 500;
}

.code-separator {
  color: var(--text-color-secondary);
  margin: 0 2px;
}

.name-text {
  font-weight: 500;
}

.text-muted {
  color: var(--text-color-secondary);
}

.empty-message {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 48px 16px;
  color: var(--text-color-secondary);
  flex: 1;
  justify-content: center;
}

.empty-message i {
  font-size: 32px;
}

:deep(.clickable-row) {
  cursor: pointer;
}

:deep(.selected-row) {
  background-color: var(--p-primary-50, #eef2ff) !important;
}
</style>
