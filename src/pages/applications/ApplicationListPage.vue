<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import {
	applicationsApi,
	type Application,
} from "@/api/applications";
import { useListState } from "@/composables/useListState";
import { useReturnTo } from "@/composables/useReturnTo";

const router = useRouter();
const { navigateToDetail } = useReturnTo();
const applications = ref<Application[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);

const { filters } = useListState({
	filters: {
		q: { type: "string" as const, key: "q" },
		type: { type: "string" as const, key: "type" },
		active: { type: "string" as const, key: "active" },
	},
});

const typeOptions = [
	{ label: "All Types", value: "" },
	{ label: "Application", value: "APPLICATION" },
	{ label: "Integration", value: "INTEGRATION" },
];

const activeOptions = [
	{ label: "All Status", value: "" },
	{ label: "Active", value: "ACTIVE" },
	{ label: "Inactive", value: "INACTIVE" },
];

const filteredApplications = computed(() => {
	let result = applications.value;

	// Filter by type
	if (filters.type.value) {
		result = result.filter((app) => app.type === filters.type.value);
	}

	// Filter by active status
	if (filters.active.value) {
		const isActive = filters.active.value === "ACTIVE";
		result = result.filter((app) => app.active === isActive);
	}

	// Filter by search query
	if (filters.q.value) {
		const query = filters.q.value.toLowerCase();
		result = result.filter(
			(app) =>
				app.code.toLowerCase().includes(query) ||
				app.name.toLowerCase().includes(query) ||
				(app.description?.toLowerCase().includes(query) ?? false),
		);
	}

	return result;
});

onMounted(async () => {
	await loadApplications();
});

async function loadApplications() {
	loading.value = true;
	error.value = null;
	try {
		const response = await applicationsApi.list();
		applications.value = response.applications;
	} catch (e) {
		error.value =
			e instanceof Error ? e.message : "Failed to load applications";
	} finally {
		loading.value = false;
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
        <h1 class="page-title">Applications</h1>
        <p class="page-subtitle">Manage applications in the platform ecosystem</p>
      </div>
      <Button
        label="Create Application"
        icon="pi pi-plus"
        @click="router.push('/applications/new')"
      />
    </header>

    <Message v-if="error" severity="error" class="error-message">{{ error }}</Message>

    <div class="fc-card">
      <div class="toolbar">
        <IconField class="search-wrapper">
          <InputIcon class="pi pi-search" />
          <InputText v-model="filters.q.value" placeholder="Search applications..." />
        </IconField>
        <div class="filter-group">
          <Select
            v-model="filters.type.value"
            :options="typeOptions"
            optionLabel="label"
            optionValue="value"
            placeholder="Type"
            class="filter-select"
          />
          <Select
            v-model="filters.active.value"
            :options="activeOptions"
            optionLabel="label"
            optionValue="value"
            placeholder="Status"
            class="filter-select"
          />
        </div>
      </div>

      <div v-if="loading" class="loading-container">
        <ProgressSpinner strokeWidth="3" />
      </div>

      <DataTable
        v-else
        :value="filteredApplications"
        paginator
        :rows="100"
        :rowsPerPageOptions="[50, 100, 250, 500]"
        stripedRows
        emptyMessage="No applications found"
      >
        <Column field="code" header="Code" sortable>
          <template #body="{ data }">
            <code class="app-code">{{ data.code }}</code>
          </template>
        </Column>
        <Column field="name" header="Name" sortable />
        <Column field="type" header="Type" sortable>
          <template #body="{ data }">
            <Tag
              :value="data.type === 'INTEGRATION' ? 'Integration' : 'Application'"
              :severity="data.type === 'INTEGRATION' ? 'info' : 'primary'"
            />
          </template>
        </Column>
        <Column field="description" header="Description">
          <template #body="{ data }">
            <span class="description-text">{{ data.description || '—' }}</span>
          </template>
        </Column>
        <Column field="active" header="Status" sortable>
          <template #body="{ data }">
            <Tag
              :value="data.active ? 'Active' : 'Inactive'"
              :severity="data.active ? 'success' : 'secondary'"
            />
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
              @click="navigateToDetail(`/applications/${data.id}`)"
            />
            <Button
              icon="pi pi-pencil"
              text
              rounded
              v-tooltip="'Edit'"
              @click="navigateToDetail(`/applications/${data.id}`)"
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
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.search-wrapper {
  flex: 1;
  min-width: 200px;
}

.search-wrapper :deep(.pi-search) {
  color: #94a3b8;
}

.filter-group {
  display: flex;
  gap: 12px;
}

.filter-select {
  min-width: 140px;
}

.loading-container {
  display: flex;
  justify-content: center;
  padding: 60px;
}

.error-message {
  margin-bottom: 16px;
}

.app-code {
  background: #f1f5f9;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 13px;
}

.description-text {
  color: #64748b;
  font-size: 14px;
}
</style>
