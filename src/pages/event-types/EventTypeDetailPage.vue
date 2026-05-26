<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, computed, onMounted } from "vue";
import { useRoute } from "vue-router";
import { useConfirm } from "primevue/useconfirm";
import {
	eventTypesApi,
	type EventType,
	type SpecVersion,
} from "@/api/event-types";
import SchemaViewerDialog from "./SchemaViewerDialog.vue";
import { useReturnTo } from "@/composables/useReturnTo";

const route = useRoute();
const { returnTo, forwardFrom } = useReturnTo();
const confirm = useConfirm();

const loading = ref(true);
const eventType = ref<EventType | null>(null);
const editing = ref(false);
const saving = ref(false);

// Schema viewer
const viewerVisible = ref(false);
const viewerSpecVersion = ref<SpecVersion | null>(null);

function viewSchema(sv: SpecVersion) {
	viewerSpecVersion.value = sv;
	viewerVisible.value = true;
}

// Edit form
const editName = ref("");
const editDescription = ref("");

const canArchive = computed(() => {
	const et = eventType.value;
	if (!et || et.status !== "CURRENT") return false;
	if (et.specVersions.length === 0) return true;
	return et.specVersions.every((sv) => sv.status === "DEPRECATED");
});

const canDelete = computed(() => {
	const et = eventType.value;
	if (!et) return false;
	if (et.status === "ARCHIVED") return true;
	if (et.status === "CURRENT" && et.specVersions.length === 0) return true;
	return (
		et.status === "CURRENT" &&
		et.specVersions.every((sv) => sv.status === "FINALISING")
	);
});

onMounted(async () => {
	const id = route.params['id'] as string;
	if (id) {
		await loadEventType(id);
	}
});

async function loadEventType(id: string) {
	loading.value = true;
	try {
		eventType.value = await eventTypesApi.get(id);
	} catch {
		eventType.value = null;
	} finally {
		loading.value = false;
	}
}

function startEditing() {
	if (eventType.value) {
		editName.value = eventType.value.name;
		editDescription.value = eventType.value.description || "";
		editing.value = true;
	}
}

function cancelEditing() {
	editing.value = false;
}

async function saveChanges() {
	if (!eventType.value) return;

	saving.value = true;
	const id = eventType.value.id;
	try {
		await eventTypesApi.update(id, {
			name: editName.value,
			description: editDescription.value,
		});
		await loadEventType(id);
		editing.value = false;
		toast.success("Success", "Event type updated");
	} catch {
	} finally {
		saving.value = false;
	}
}

function getSchemaStatusSeverity(status: string) {
	switch (status) {
		case "CURRENT":
			return "success";
		case "FINALISING":
			return "info";
		case "DEPRECATED":
			return "warn";
		default:
			return "secondary";
	}
}

function formatSchemaType(type: string) {
	switch (type) {
		case "JSON_SCHEMA":
			return "JSON Schema";
		case "PROTO":
			return "Protocol Buffers";
		case "XSD":
			return "XML Schema";
		default:
			return type;
	}
}

function confirmFinalise(sv: SpecVersion) {
	confirm.require({
		message: `Finalise schema version ${sv.version}? This makes it the current version.`,
		header: "Finalise Schema",
		icon: "pi pi-check-circle",
		acceptLabel: "Finalise",
		accept: () => finaliseSchema(sv.version),
	});
}

async function finaliseSchema(version: string) {
	if (!eventType.value) return;
	try {
		eventType.value = await eventTypesApi.finaliseSchema(
			eventType.value.id,
			version,
		);
		toast.success("Success", `Schema ${version} finalised`);
	} catch {
	}
}

function confirmDeprecate(sv: SpecVersion) {
	confirm.require({
		message: `Deprecate schema version ${sv.version}?`,
		header: "Deprecate Schema",
		icon: "pi pi-exclamation-triangle",
		acceptLabel: "Deprecate",
		acceptClass: "p-button-warning",
		accept: () => deprecateSchema(sv.version),
	});
}

async function deprecateSchema(version: string) {
	if (!eventType.value) return;
	try {
		eventType.value = await eventTypesApi.deprecateSchema(
			eventType.value.id,
			version,
		);
		toast.success("Success", `Schema ${version} deprecated`);
	} catch {
	}
}

function confirmArchive() {
	confirm.require({
		message:
			"Archive this event type? No new events can be created for archived types.",
		header: "Archive Event Type",
		icon: "pi pi-exclamation-triangle",
		acceptLabel: "Archive",
		acceptClass: "p-button-warning",
		accept: archiveEventType,
	});
}

async function archiveEventType() {
	if (!eventType.value) return;
	try {
		eventType.value = await eventTypesApi.archive(eventType.value.id);
		toast.success("Success", "Event type archived");
	} catch {
	}
}

function confirmDelete() {
	confirm.require({
		message: "Delete this event type? This cannot be undone.",
		header: "Delete Event Type",
		icon: "pi pi-exclamation-triangle",
		acceptLabel: "Delete",
		acceptClass: "p-button-danger",
		accept: deleteEventType,
	});
}

async function deleteEventType() {
	if (!eventType.value) return;
	try {
		await eventTypesApi.delete(eventType.value.id);
		toast.success("Success", "Event type deleted");
		returnTo("/event-types");
	} catch {
	}
}
</script>

<template>
  <div class="page-container">
    <div v-if="loading" class="loading-container">
      <ProgressSpinner strokeWidth="3" />
    </div>

    <template v-else-if="eventType">
      <!-- Header -->
      <header class="page-header">
        <div class="header-content">
          <Button
            icon="pi pi-arrow-left"
            text
            severity="secondary"
            @click="returnTo('/event-types')"
            v-tooltip="'Back to list'"
          />
          <div class="header-text">
            <h1 class="page-title">{{ eventType.name }}</h1>
            <div class="code-display">
              <span class="code-segment app">{{ eventType.application }}</span>
              <span class="code-separator">:</span>
              <span class="code-segment subdomain">{{ eventType.subdomain }}</span>
              <span class="code-separator">:</span>
              <span class="code-segment aggregate">{{ eventType.aggregate }}</span>
              <span class="code-separator">:</span>
              <span class="code-segment event">{{ eventType.event }}</span>
            </div>
          </div>
          <Tag
            :value="eventType.status"
            :severity="eventType.status === 'CURRENT' ? 'success' : 'secondary'"
          />
        </div>
      </header>

      <!-- Details Card -->
      <div class="section-card">
        <div class="card-header">
          <h3>Event Type Details</h3>
          <Button
            v-if="!editing && eventType.status !== 'ARCHIVED'"
            icon="pi pi-pencil"
            label="Edit"
            text
            @click="startEditing"
          />
        </div>
        <div class="card-content">
          <template v-if="editing">
            <div class="form-field">
              <label>Name</label>
              <InputText v-model="editName" class="full-width" />
            </div>
            <div class="form-field">
              <label>Description</label>
              <Textarea v-model="editDescription" :rows="3" class="full-width" />
            </div>
            <div class="form-actions">
              <Button label="Cancel" severity="secondary" outlined @click="cancelEditing" />
              <Button label="Save" :loading="saving" @click="saveChanges" />
            </div>
          </template>

          <template v-else>
            <div class="detail-grid">
              <div class="detail-item">
                <label>Name</label>
                <span>{{ eventType.name }}</span>
              </div>
              <div class="detail-item">
                <label>Description</label>
                <span>{{ eventType.description || '—' }}</span>
              </div>
              <div class="detail-item">
                <label>Client Scoped</label>
                <span>
                  <Tag
                    :value="eventType.clientScoped ? 'Yes' : 'No'"
                    :severity="eventType.clientScoped ? 'info' : 'secondary'"
                  />
                </span>
              </div>
            </div>
          </template>
        </div>
      </div>

      <!-- Schema Versions Card -->
      <div class="section-card">
        <div class="card-header">
          <h3>Schema Versions</h3>
          <Button
            v-if="eventType.status === 'CURRENT'"
            icon="pi pi-plus"
            label="Add Schema"
            text
            @click="forwardFrom(`/event-types/${eventType.id}/add-schema`)"
          />
        </div>
        <div class="card-content">
          <div v-if="eventType.specVersions.length === 0" class="empty-state">
            <i class="pi pi-file"></i>
            <p>No schema versions defined yet.</p>
            <Button
              v-if="eventType.status === 'CURRENT'"
              label="Add First Schema"
              icon="pi pi-plus"
              @click="forwardFrom(`/event-types/${eventType.id}/add-schema`)"
            />
          </div>

          <DataTable v-else :value="eventType.specVersions" size="small">
            <Column header="Version" style="width: 15%">
              <template #body="{ data }">
                <span class="version-text">{{ data.version }}</span>
              </template>
            </Column>
            <Column header="MIME Type" style="width: 20%">
              <template #body="{ data }">
                <code class="mime-type">{{ data.mimeType }}</code>
              </template>
            </Column>
            <Column header="Schema Type" style="width: 20%">
              <template #body="{ data }">
                {{ formatSchemaType(data.schemaType) }}
              </template>
            </Column>
            <Column header="Status" style="width: 15%">
              <template #body="{ data }">
                <Tag :value="data.status" :severity="getSchemaStatusSeverity(data.status)" />
              </template>
            </Column>
            <Column header="Actions" style="width: 30%">
              <template #body="{ data }">
                <div class="action-buttons">
                  <Button
                    v-if="data.schema"
                    icon="pi pi-eye"
                    rounded
                    text
                    v-tooltip="'View Schema'"
                    @click="viewSchema(data)"
                  />
                  <Button
                    v-if="data.status === 'FINALISING'"
                    icon="pi pi-check"
                    rounded
                    text
                    severity="success"
                    v-tooltip="'Finalise'"
                    @click="confirmFinalise(data)"
                  />
                  <Button
                    v-if="data.status === 'CURRENT'"
                    icon="pi pi-ban"
                    rounded
                    text
                    severity="warn"
                    v-tooltip="'Deprecate'"
                    @click="confirmDeprecate(data)"
                  />
                </div>
              </template>
            </Column>
          </DataTable>
        </div>
      </div>

      <!-- Danger Zone -->
      <div class="section-card danger-zone">
        <div class="card-header danger-header">
          <h3>Danger Zone</h3>
        </div>
        <div class="card-content">
          <div class="danger-actions">
            <div v-if="eventType.status === 'CURRENT'" class="danger-item">
              <div class="danger-info">
                <strong>Archive Event Type</strong>
                <p>Requires all schemas to be deprecated first.</p>
              </div>
              <Button
                label="Archive"
                severity="warn"
                outlined
                :disabled="!canArchive"
                @click="confirmArchive"
              />
            </div>

            <div class="danger-item">
              <div class="danger-info">
                <strong>Delete Event Type</strong>
                <p>Permanently delete this event type.</p>
              </div>
              <Button
                label="Delete"
                severity="danger"
                outlined
                :disabled="!canDelete"
                @click="confirmDelete"
              />
            </div>
          </div>
        </div>
      </div>
      <!-- Schema Viewer Dialog -->
      <SchemaViewerDialog
        v-model:visible="viewerVisible"
        :specVersion="viewerSpecVersion"
        :eventCode="eventType.code"
      />
    </template>

    <Message v-else severity="error">Event type not found</Message>
  </div>
</template>

<style scoped>
.page-container {
  max-width: 1000px;
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

.empty-state {
  text-align: center;
  padding: 48px;
  color: #64748b;
}

.empty-state i {
  font-size: 48px;
  color: #cbd5e1;
  margin-bottom: 16px;
}

.version-text {
  font-family: monospace;
  font-weight: 500;
}

.mime-type {
  font-size: 13px;
  background: #f1f5f9;
  padding: 2px 6px;
  border-radius: 4px;
}

.action-buttons {
  display: flex;
  gap: 4px;
}

.danger-header h3 {
  color: #dc2626;
}

.danger-actions {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.danger-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: #fafafa;
  border-radius: 8px;
  border: 1px solid #e5e7eb;
}

.danger-info strong {
  display: block;
  margin-bottom: 4px;
}

.danger-info p {
  margin: 0;
  font-size: 13px;
  color: #64748b;
}
</style>
