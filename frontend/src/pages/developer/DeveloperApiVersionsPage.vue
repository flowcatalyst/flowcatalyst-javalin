<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  developerApi,
  type DeveloperApplicationSummary,
  type OpenApiVersionSummary,
} from "@/api/developer";

const route = useRoute();
const router = useRouter();

const appId = ref<string>(String(route.params["id"]));
const app = ref<DeveloperApplicationSummary | null>(null);
const versions = ref<OpenApiVersionSummary[]>([]);
const loading = ref(true);

async function load() {
  loading.value = true;
  try {
    const [appRes, versionsRes] = await Promise.all([
      developerApi.getApplication(appId.value),
      developerApi.listOpenApiVersions(appId.value),
    ]);
    app.value = appRes;
    versions.value = versionsRes.items;
  } finally {
    loading.value = false;
  }
}

onMounted(load);

function formatSyncedAt(iso: string): string {
  return new Date(iso).toLocaleString();
}
</script>

<template>
  <div class="page-container">
    <div v-if="loading" class="loading-container">
      <ProgressSpinner strokeWidth="3" />
    </div>

    <template v-else>
      <header class="page-header">
        <div class="header-content">
          <Button
            icon="pi pi-arrow-left"
            text
            severity="secondary"
            @click="router.push(`/developer/applications/${appId}`)"
            v-tooltip="'Back'"
          />
          <div class="header-text">
            <h1 class="page-title">API Versions</h1>
            <p class="page-subtitle">
              <span v-if="app" class="app-code">{{ app.code }}</span>
              · Every sync that introduces a change creates a new row.
            </p>
          </div>
        </div>
      </header>

      <div class="fc-card table-card">
        <DataTable :value="versions" size="small">
          <Column header="Version" style="width: 15%">
            <template #body="{ data }">
              <Tag
                :value="data.version"
                :severity="data.status === 'CURRENT' ? 'success' : 'secondary'"
              />
            </template>
          </Column>

          <Column header="Status" style="width: 12%">
            <template #body="{ data }">
              <span :class="['status-text', data.status.toLowerCase()]">
                {{ data.status }}
              </span>
            </template>
          </Column>

          <Column header="Breaking" style="width: 10%">
            <template #body="{ data }">
              <Tag
                v-if="data.hasBreaking"
                value="breaking"
                severity="danger"
                v-tooltip.top="'Removed paths, schemas, or operations'"
              />
              <span v-else class="text-muted">—</span>
            </template>
          </Column>

          <Column header="Changes" style="width: 48%">
            <template #body="{ data }">
              <span class="change-notes">{{ data.changeNotesText || "Initial version" }}</span>
            </template>
          </Column>

          <Column header="Synced" style="width: 15%">
            <template #body="{ data }">
              <span class="text-muted text-sm">{{ formatSyncedAt(data.syncedAt) }}</span>
            </template>
          </Column>

          <template #empty>
            <div class="empty-message">
              <i class="pi pi-inbox"></i>
              <span>No OpenAPI versions yet.</span>
            </div>
          </template>
        </DataTable>
      </div>
    </template>
  </div>
</template>

<style scoped>
.loading-container {
  display: flex;
  justify-content: center;
  padding: 64px 0;
}

.page-header {
  margin-bottom: 24px;
}

.header-content {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-text {
  flex: 1;
  min-width: 0;
}

.app-code {
  font-family: ui-monospace, SFMono-Regular, monospace;
  color: var(--text-color-secondary);
}

.table-card {
  padding: 0;
  overflow: hidden;
}

.status-text {
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.status-text.current {
  color: var(--p-green-600, #16a34a);
}

.status-text.archived {
  color: var(--text-color-secondary);
}

.change-notes {
  font-size: 13px;
  color: var(--text-color);
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
</style>
