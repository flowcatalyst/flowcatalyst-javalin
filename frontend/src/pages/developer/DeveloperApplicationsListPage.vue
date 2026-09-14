<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { developerApi, type DeveloperApplicationSummary } from "@/api/developer";

const router = useRouter();

const applications = ref<DeveloperApplicationSummary[]>([]);
const loading = ref(true);

async function load() {
  loading.value = true;
  try {
    const res = await developerApi.listApplications();
    applications.value = res.items;
  } finally {
    loading.value = false;
  }
}

onMounted(load);

function openApp(app: DeveloperApplicationSummary) {
  router.push(`/developer/applications/${app.id}`);
}

function formatSyncedAt(iso: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return d.toLocaleString();
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Developer Portal</h1>
        <p class="page-subtitle">
          API documentation and event types for the applications you have access to.
        </p>
      </div>
    </header>

    <div class="fc-card table-card">
      <DataTable
        :value="applications"
        :loading="loading"
        size="small"
        :rowClass="() => 'clickable-row'"
        @row-click="(e) => openApp(e.data)"
      >
        <Column field="code" header="Code" style="width: 18%">
          <template #body="{ data }">
            <span class="font-mono">{{ data.code }}</span>
          </template>
        </Column>

        <Column field="name" header="Application" style="width: 25%">
          <template #body="{ data }">
            <div class="app-name-cell">
              <i v-if="data.code === 'platform'" class="pi pi-star-fill platform-marker" v-tooltip.top="'This platform'"></i>
              <span class="name-text">{{ data.name }}</span>
            </div>
          </template>
        </Column>

        <Column field="description" header="Description" style="width: 30%">
          <template #body="{ data }">
            <span class="text-muted">{{ data.description || "—" }}</span>
          </template>
        </Column>

        <Column header="API Version" style="width: 12%">
          <template #body="{ data }">
            <Tag
              v-if="data.currentVersion"
              :value="data.currentVersion"
              severity="info"
            />
            <span v-else class="text-muted">—</span>
          </template>
        </Column>

        <Column header="Last Synced" style="width: 15%">
          <template #body="{ data }">
            <span class="text-muted text-sm">{{ formatSyncedAt(data.currentSyncedAt) }}</span>
          </template>
        </Column>

        <template #empty>
          <div class="empty-message">
            <i class="pi pi-inbox"></i>
            <span>No applications visible. Ask an admin to grant access to one.</span>
          </div>
        </template>
      </DataTable>
    </div>
  </div>
</template>

<style scoped>
.table-card {
  padding: 0;
  overflow: hidden;
}

.app-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.platform-marker {
  color: var(--p-amber-500, #f59e0b);
  font-size: 12px;
}

.font-mono {
  font-family: ui-monospace, SFMono-Regular, monospace;
  font-size: 13px;
}

.name-text {
  font-weight: 500;
}

.text-muted {
  color: var(--text-color-secondary);
}

.text-sm {
  font-size: 13px;
}

.empty-message {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 48px 16px;
  color: var(--text-color-secondary);
}

.empty-message i {
  font-size: 32px;
}

:deep(.clickable-row) {
  cursor: pointer;
}
</style>
