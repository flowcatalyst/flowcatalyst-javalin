<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { rolesApi, type Role, type RoleSource } from "@/api/roles";
import { useReturnTo } from "@/composables/useReturnTo";

const route = useRoute();
const router = useRouter();
const { returnTo } = useReturnTo();

const role = ref<Role | null>(null);
const loading = ref(true);
const error = ref<string | null>(null);

const roleName = computed(() => route.params['roleName'] as string);

const canEdit = computed(() => role.value?.source === "DATABASE");

onMounted(async () => {
	await loadRole();
});

async function loadRole() {
	loading.value = true;
	error.value = null;

	try {
		role.value = await rolesApi.get(roleName.value);
	} catch (e) {
		error.value = e instanceof Error ? e.message : "Failed to load role";
	} finally {
		loading.value = false;
	}
}

function goBack() {
	returnTo("/authorization/roles");
}

function editRole() {
	router.push(
		`/authorization/roles/${encodeURIComponent(roleName.value)}/edit`,
	);
}

function getSourceSeverity(source: RoleSource) {
	switch (source) {
		case "CODE":
			return "info";
		case "DATABASE":
			return "success";
		case "SDK":
			return "warn";
		default:
			return "secondary";
	}
}

function getSourceLabel(source: RoleSource) {
	switch (source) {
		case "CODE":
			return "Code-defined";
		case "DATABASE":
			return "Admin-created";
		case "SDK":
			return "SDK-registered";
		default:
			return source;
	}
}

function formatDate(dateStr: string | undefined) {
	if (!dateStr) return "—";
	return new Date(dateStr).toLocaleString();
}

// Parse permission string into parts
function parsePermission(permission: string) {
	const parts = permission.split(":");
	return {
		application: parts[0] || "",
		context: parts[1] || "",
		aggregate: parts[2] || "",
		action: parts[3] || "",
	};
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div class="header-left">
        <Button
          icon="pi pi-arrow-left"
          text
          rounded
          severity="secondary"
          @click="goBack"
          v-tooltip.right="'Back to roles'"
        />
        <div>
          <h1 class="page-title">{{ role?.displayName || role?.shortName || 'Role Details' }}</h1>
          <p class="page-subtitle">{{ role?.name }}</p>
        </div>
      </div>
      <div v-if="role" class="header-right">
        <Button v-if="canEdit" label="Edit Role" icon="pi pi-pencil" @click="editRole" />
        <Tag :value="getSourceLabel(role.source)" :severity="getSourceSeverity(role.source)" />
      </div>
    </header>

    <div v-if="loading" class="loading-container">
      <ProgressSpinner strokeWidth="3" />
    </div>

    <div v-else-if="error" class="error-container">
      <i class="pi pi-exclamation-triangle"></i>
      <span>{{ error }}</span>
      <Button label="Go Back" @click="goBack" />
    </div>

    <template v-else-if="role">
      <!-- Role Info Card -->
      <div class="fc-card info-card">
        <div class="info-grid">
          <div class="info-item">
            <label>Role Name</label>
            <span class="monospace">{{ role.name }}</span>
          </div>
          <div class="info-item">
            <label>Display Name</label>
            <span>{{ role.displayName || '—' }}</span>
          </div>
          <div class="info-item">
            <label>Application</label>
            <Tag :value="role.applicationCode" severity="secondary" />
          </div>
          <div class="info-item">
            <label>Source</label>
            <Tag :value="getSourceLabel(role.source)" :severity="getSourceSeverity(role.source)" />
          </div>
          <div class="info-item full-width">
            <label>Description</label>
            <span>{{ role.description || 'No description provided' }}</span>
          </div>
          <div class="info-item">
            <label>Created</label>
            <span>{{ formatDate(role.createdAt) }}</span>
          </div>
          <div class="info-item">
            <label>Updated</label>
            <span>{{ formatDate(role.updatedAt) }}</span>
          </div>
        </div>
      </div>

      <!-- Permissions Card -->
      <div class="fc-card permissions-card">
        <div class="card-header">
          <h2>Permissions</h2>
          <span class="permission-count">{{ role.permissions?.length || 0 }} permissions</span>
        </div>

        <DataTable
          v-if="role.permissions && role.permissions.length > 0"
          :value="role.permissions.map((p) => ({ permission: p, ...parsePermission(p) }))"
          :paginator="role.permissions.length > 10"
          :rows="10"
          :rowsPerPageOptions="[10, 25, 50]"
          size="small"
        >
          <Column field="permission" header="Permission" style="width: 40%">
            <template #body="{ data }">
              <span class="permission-string">{{ data.permission }}</span>
            </template>
          </Column>
          <Column field="application" header="Application" style="width: 15%">
            <template #body="{ data }">
              <Tag :value="data.application" severity="secondary" />
            </template>
          </Column>
          <Column field="context" header="Context" style="width: 15%">
            <template #body="{ data }">
              <span>{{ data.context }}</span>
            </template>
          </Column>
          <Column field="aggregate" header="Aggregate" style="width: 15%">
            <template #body="{ data }">
              <span>{{ data.aggregate }}</span>
            </template>
          </Column>
          <Column field="action" header="Action" style="width: 15%">
            <template #body="{ data }">
              <Tag :value="data.action" :severity="getActionSeverity(data.action)" />
            </template>
          </Column>
        </DataTable>

        <div v-else class="empty-permissions">
          <i class="pi pi-lock"></i>
          <span>This role has no permissions assigned</span>
        </div>
      </div>
    </template>
  </div>
</template>

<script lang="ts">
function getActionSeverity(action: string) {
  switch (action) {
    case 'view':
      return 'info';
    case 'create':
      return 'success';
    case 'update':
      return 'warn';
    case 'delete':
      return 'danger';
    default:
      return 'secondary';
  }
}
</script>

<style scoped>
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.loading-container,
.error-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px;
  gap: 16px;
  color: #64748b;
}

.error-container i {
  font-size: 48px;
  color: #ef4444;
}

.info-card {
  margin-bottom: 24px;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 24px;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.info-item.full-width {
  grid-column: span 4;
}

.info-item label {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.info-item span {
  font-size: 14px;
  color: #1e293b;
}

.monospace {
  font-family: monospace;
}

.permissions-card {
  padding: 0;
  overflow: hidden;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #e2e8f0;
}

.card-header h2 {
  font-size: 16px;
  font-weight: 600;
  color: #1e293b;
  margin: 0;
}

.permission-count {
  font-size: 13px;
  color: #64748b;
}

.permission-string {
  font-family: monospace;
  font-size: 13px;
  color: #475569;
}

.empty-permissions {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px;
  color: #64748b;
  gap: 12px;
}

.empty-permissions i {
  font-size: 32px;
  color: #cbd5e1;
}

:deep(.p-datatable .p-datatable-thead > tr > th) {
  background: #f8fafc;
  color: #475569;
  font-weight: 600;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

@media (max-width: 1024px) {
  .info-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .info-item.full-width {
    grid-column: span 2;
  }
}

@media (max-width: 640px) {
  .info-grid {
    grid-template-columns: 1fr;
  }

  .info-item.full-width {
    grid-column: span 1;
  }
}
</style>
