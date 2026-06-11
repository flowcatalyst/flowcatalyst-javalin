<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, onMounted } from "vue";
import { useRoute } from "vue-router";
import { useConfirm } from "primevue/useconfirm";
import { connectionsApi, type Connection } from "@/api/connections";
import { useReturnTo } from "@/composables/useReturnTo";

const route = useRoute();
const { returnTo } = useReturnTo();
const confirm = useConfirm();

const loading = ref(true);
const connection = ref<Connection | null>(null);
const editing = ref(false);
const saving = ref(false);

// Edit form
const editName = ref("");
const editDescription = ref("");
const editExternalId = ref("");

onMounted(async () => {
	const id = route.params['id'] as string;
	if (id) {
		await loadConnection(id);
	}
});

async function loadConnection(id: string) {
	loading.value = true;
	try {
		connection.value = await connectionsApi.get(id);
	} catch {
		connection.value = null;
	} finally {
		loading.value = false;
	}
}

function startEditing() {
	if (connection.value) {
		editName.value = connection.value.name;
		editDescription.value = connection.value.description || "";
		editExternalId.value = connection.value.externalId || "";
		editing.value = true;
	}
}

function cancelEditing() {
	editing.value = false;
}

async function saveChanges() {
	if (!connection.value) return;

	saving.value = true;
	const id = connection.value.id;
	try {
		await connectionsApi.update(id, {
			name: editName.value,
			description: editDescription.value || undefined,
			externalId: editExternalId.value || undefined,
		});
		await loadConnection(id);
		editing.value = false;
		toast.success("Success", "Connection updated");
	} catch {
	} finally {
		saving.value = false;
	}
}

function confirmActivate() {
	confirm.require({
		message: "Activate this connection?",
		header: "Activate Connection",
		icon: "pi pi-check-circle",
		acceptLabel: "Activate",
		accept: activateConnection,
	});
}

async function activateConnection() {
	if (!connection.value) return;
	try {
		await connectionsApi.activate(connection.value.id);
		connection.value = await connectionsApi.get(connection.value.id);
		toast.success("Success", "Connection activated");
	} catch {
	}
}

function confirmPause() {
	confirm.require({
		message: "Pause this connection? Subscriptions using it will stop dispatching.",
		header: "Pause Connection",
		icon: "pi pi-pause",
		acceptLabel: "Pause",
		acceptClass: "p-button-warning",
		accept: pauseConnection,
	});
}

async function pauseConnection() {
	if (!connection.value) return;
	try {
		await connectionsApi.pause(connection.value.id);
		connection.value = await connectionsApi.get(connection.value.id);
		toast.success("Success", "Connection paused");
	} catch {
	}
}

function confirmDelete() {
	confirm.require({
		message: "Delete this connection? This action cannot be undone.",
		header: "Delete Connection",
		icon: "pi pi-exclamation-triangle",
		acceptLabel: "Delete",
		acceptClass: "p-button-danger",
		accept: deleteConnection,
	});
}

async function deleteConnection() {
	if (!connection.value) return;
	try {
		await connectionsApi.delete(connection.value.id);
		toast.success("Success", "Connection deleted");
		returnTo("/connections");
	} catch {
	}
}

// Takes the wire `status` (plain string in the generated type — the spec has
// no enums); the default branch covers anything outside ACTIVE/PAUSED.
function getStatusSeverity(status: string) {
	switch (status) {
		case "ACTIVE":
			return "success";
		case "PAUSED":
			return "warn";
		default:
			return "secondary";
	}
}

function formatDate(dateString: string) {
	return new Date(dateString).toLocaleString();
}

function getScopeLabel(conn: Connection) {
	if (conn.clientIdentifier) {
		return conn.clientIdentifier;
	}
	return "Anchor-level (no client)";
}
</script>

<template>
  <div class="page-container">
    <div v-if="loading" class="loading-container">
      <ProgressSpinner strokeWidth="3" />
    </div>

    <template v-else-if="connection">
      <!-- Header -->
      <header class="page-header">
        <div class="header-content">
          <Button
            icon="pi pi-arrow-left"
            text
            severity="secondary"
            @click="returnTo('/connections')"
            v-tooltip="'Back to list'"
          />
          <div class="header-text">
            <h1 class="page-title">{{ connection.name }}</h1>
            <code class="conn-code">{{ connection.code }}</code>
          </div>
          <Tag :value="connection.status" :severity="getStatusSeverity(connection.status)" />
        </div>
      </header>

      <!-- Details Card -->
      <div class="section-card">
        <div class="card-header">
          <h3>Connection Details</h3>
          <Button v-if="!editing" icon="pi pi-pencil" label="Edit" text @click="startEditing" />
        </div>
        <div class="card-content">
          <template v-if="editing">
            <div class="form-field">
              <label>Name</label>
              <InputText v-model="editName" class="full-width" />
            </div>
            <div class="form-field">
              <label>Description</label>
              <Textarea v-model="editDescription" class="full-width" rows="3" />
            </div>
            <div class="form-field">
              <label>External ID</label>
              <InputText v-model="editExternalId" class="full-width" />
            </div>
            <div class="form-actions">
              <Button label="Cancel" severity="secondary" outlined @click="cancelEditing" />
              <Button label="Save" :loading="saving" @click="saveChanges" />
            </div>
          </template>

          <template v-else>
            <div class="detail-grid">
              <div class="detail-item">
                <label>Code</label>
                <code>{{ connection.code }}</code>
              </div>
              <div class="detail-item">
                <label>Name</label>
                <span>{{ connection.name }}</span>
              </div>
              <div class="detail-item full-width" v-if="connection.description">
                <label>Description</label>
                <span>{{ connection.description }}</span>
              </div>
              <div class="detail-item" v-if="connection.externalId">
                <label>External ID</label>
                <code>{{ connection.externalId }}</code>
              </div>
              <div class="detail-item">
                <label>Service Account</label>
                <span>{{ connection.serviceAccountId }}</span>
              </div>
              <div class="detail-item">
                <label>Scope</label>
                <span>{{ getScopeLabel(connection) }}</span>
              </div>
              <div class="detail-item">
                <label>Status</label>
                <Tag :value="connection.status" :severity="getStatusSeverity(connection.status)" />
              </div>
              <div class="detail-item">
                <label>Created</label>
                <span>{{ formatDate(connection.createdAt) }}</span>
              </div>
              <div class="detail-item">
                <label>Updated</label>
                <span>{{ formatDate(connection.updatedAt) }}</span>
              </div>
            </div>
          </template>
        </div>
      </div>

      <!-- Actions Card -->
      <div class="section-card">
        <div class="card-header">
          <h3>Actions</h3>
        </div>
        <div class="card-content">
          <div class="action-items">
            <div v-if="connection.status === 'PAUSED'" class="action-item">
              <div class="action-info">
                <strong>Activate Connection</strong>
                <p>Enable this connection for event delivery.</p>
              </div>
              <Button label="Activate" severity="success" outlined @click="confirmActivate" />
            </div>

            <div v-if="connection.status === 'ACTIVE'" class="action-item">
              <div class="action-info">
                <strong>Pause Connection</strong>
                <p>Temporarily stop event delivery through this connection.</p>
              </div>
              <Button label="Pause" icon="pi pi-pause" severity="warn" outlined @click="confirmPause" />
            </div>

            <div class="action-item">
              <div class="action-info">
                <strong>Delete Connection</strong>
                <p>Permanently delete this connection. Cannot be undone.</p>
              </div>
              <Button label="Delete" icon="pi pi-trash" severity="danger" outlined @click="confirmDelete" />
            </div>
          </div>
        </div>
      </div>
    </template>

    <Message v-else severity="error">Connection not found</Message>
  </div>
</template>

<style scoped>
.page-container {
  max-width: 900px;
}

.loading-container {
  display: flex;
  justify-content: center;
  padding: 60px;
}

.header-content {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.header-text {
  flex: 1;
}

.conn-code {
  display: inline-block;
  margin-top: 4px;
  background: #f1f5f9;
  padding: 4px 10px;
  border-radius: 4px;
  font-size: 14px;
  color: #475569;
}

.section-card {
  margin-bottom: 24px;
  background: white;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  overflow: hidden;
}

.card-content {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #e2e8f0;
}

.card-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.detail-item.full-width {
  grid-column: 1 / -1;
}

.detail-item label {
  font-size: 12px;
  font-weight: 500;
  color: #64748b;
  text-transform: uppercase;
}

.form-field {
  margin-bottom: 20px;
}

.form-field label {
  display: block;
  margin-bottom: 6px;
  font-weight: 500;
}

.full-width {
  width: 100%;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid #e2e8f0;
}

.action-items {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.action-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: #fafafa;
  border-radius: 8px;
  border: 1px solid #e5e7eb;
}

.action-info strong {
  display: block;
  margin-bottom: 4px;
}

.action-info p {
  margin: 0;
  font-size: 13px;
  color: #64748b;
}

@media (max-width: 640px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
