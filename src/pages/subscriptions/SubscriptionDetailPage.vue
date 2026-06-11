<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, onMounted } from "vue";
import { useRoute } from "vue-router";
import { useConfirm } from "primevue/useconfirm";
import {
	subscriptionsApi,
	type Subscription,
	type SubscriptionMode,
} from "@/api/subscriptions";
import { useReturnTo } from "@/composables/useReturnTo";

const route = useRoute();
const { returnTo } = useReturnTo();
const confirm = useConfirm();

const loading = ref(true);
const subscription = ref<Subscription | null>(null);
const editing = ref(false);
const saving = ref(false);

// Edit form
const editName = ref("");
const editDescription = ref("");
const editEndpoint = ref("");
const editConnectionId = ref("");
const editQueue = ref("");
const editMaxAgeSeconds = ref<number | null>(null);
const editDelaySeconds = ref<number | null>(null);
const editSequence = ref<number | null>(null);
const editTimeoutSeconds = ref<number | null>(null);
const editMode = ref<SubscriptionMode>("IMMEDIATE");

const modeOptions = [
	{ label: "Immediate", value: "IMMEDIATE" },
	{ label: "Next on Error", value: "NEXT_ON_ERROR" },
	{ label: "Block on Error", value: "BLOCK_ON_ERROR" },
];

onMounted(async () => {
	const id = route.params['id'] as string;
	if (id) {
		await loadSubscription(id);
	}
});

async function loadSubscription(id: string) {
	loading.value = true;
	try {
		subscription.value = await subscriptionsApi.get(id);
	} catch {
		subscription.value = null;
	} finally {
		loading.value = false;
	}
}

function startEditing() {
	if (subscription.value) {
		editName.value = subscription.value.name;
		editDescription.value = subscription.value.description || "";
		editEndpoint.value = subscription.value.endpoint || "";
		editConnectionId.value = subscription.value.connectionId || "";
		editQueue.value = subscription.value.queue || "";
		editMaxAgeSeconds.value = subscription.value.maxAgeSeconds;
		editDelaySeconds.value = subscription.value.delaySeconds;
		editSequence.value = subscription.value.sequence;
		editTimeoutSeconds.value = subscription.value.timeoutSeconds;
		// Wire mode is plain string; the form narrows to the known wire values.
		editMode.value = subscription.value.mode as SubscriptionMode;
		editing.value = true;
	}
}

function cancelEditing() {
	editing.value = false;
}

async function saveChanges() {
	if (!subscription.value) return;

	saving.value = true;
	const id = subscription.value.id;
	try {
		await subscriptionsApi.update(id, {
			name: editName.value,
			description: editDescription.value || undefined,
			endpoint: editEndpoint.value,
			connectionId: editConnectionId.value,
			queue: editQueue.value,
			maxAgeSeconds: editMaxAgeSeconds.value || undefined,
			delaySeconds: editDelaySeconds.value || undefined,
			sequence: editSequence.value || undefined,
			timeoutSeconds: editTimeoutSeconds.value || undefined,
			mode: editMode.value,
		});
		await loadSubscription(id);
		editing.value = false;
		toast.success("Success", "Subscription updated");
	} catch {
	} finally {
		saving.value = false;
	}
}

function confirmPause() {
	confirm.require({
		message: "Pause this subscription? It will stop creating dispatch jobs.",
		header: "Pause Subscription",
		icon: "pi pi-pause",
		acceptLabel: "Pause",
		acceptClass: "p-button-warning",
		accept: pauseSubscription,
	});
}

async function pauseSubscription() {
	if (!subscription.value) return;
	try {
		await subscriptionsApi.pause(subscription.value.id);
		subscription.value = await subscriptionsApi.get(subscription.value.id);
		toast.success("Success", "Subscription paused");
	} catch {
	}
}

function confirmResume() {
	confirm.require({
		message: "Resume this subscription?",
		header: "Resume Subscription",
		icon: "pi pi-play",
		acceptLabel: "Resume",
		accept: resumeSubscription,
	});
}

async function resumeSubscription() {
	if (!subscription.value) return;
	try {
		await subscriptionsApi.resume(subscription.value.id);
		subscription.value = await subscriptionsApi.get(subscription.value.id);
		toast.success("Success", "Subscription resumed");
	} catch {
	}
}

function confirmDelete() {
	confirm.require({
		message: "Delete this subscription? This action cannot be undone.",
		header: "Delete Subscription",
		icon: "pi pi-exclamation-triangle",
		acceptLabel: "Delete",
		acceptClass: "p-button-danger",
		accept: deleteSubscription,
	});
}

async function deleteSubscription() {
	if (!subscription.value) return;
	try {
		await subscriptionsApi.delete(subscription.value.id);
		toast.success("Success", "Subscription deleted");
		returnTo("/subscriptions");
	} catch {
	}
}

// Wire status/mode are plain strings (spec carries no enum); default covers unknowns.
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

function getModeLabel(mode: string) {
	switch (mode) {
		case "IMMEDIATE":
			return "Immediate";
		case "NEXT_ON_ERROR":
			return "Next on Error";
		case "BLOCK_ON_ERROR":
			return "Block on Error";
		default:
			return mode;
	}
}

function formatDate(dateString: string) {
	return new Date(dateString).toLocaleString();
}

function getScopeLabel(sub: Subscription) {
	if (sub.clientIdentifier) {
		return sub.clientIdentifier;
	}
	return "Anchor-level (no client)";
}
</script>

<template>
  <div class="page-container">
    <div v-if="loading" class="loading-container">
      <ProgressSpinner strokeWidth="3" />
    </div>

    <template v-else-if="subscription">
      <!-- Header -->
      <header class="page-header">
        <div class="header-content">
          <Button
            icon="pi pi-arrow-left"
            text
            severity="secondary"
            @click="returnTo('/subscriptions')"
            v-tooltip="'Back to list'"
          />
          <div class="header-text">
            <h1 class="page-title">{{ subscription.name }}</h1>
            <code class="sub-code">{{ subscription.code }}</code>
          </div>
          <Tag :value="subscription.status" :severity="getStatusSeverity(subscription.status)" />
        </div>
      </header>

      <!-- Details Card -->
      <div class="section-card">
        <div class="card-header">
          <h3>Subscription Details</h3>
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
              <label>Endpoint URL</label>
              <InputText v-model="editEndpoint" class="full-width" />
            </div>
            <div class="form-field">
              <label>Connection ID</label>
              <InputText v-model="editConnectionId" class="full-width" />
            </div>
            <div class="form-field">
              <label>Queue</label>
              <InputText v-model="editQueue" class="full-width" />
            </div>
            <div class="form-row">
              <div class="form-field">
                <label>Max Age (seconds)</label>
                <InputNumber v-model="editMaxAgeSeconds" :min="1" class="full-width" />
              </div>
              <div class="form-field">
                <label>Timeout (seconds)</label>
                <InputNumber v-model="editTimeoutSeconds" :min="1" class="full-width" />
              </div>
            </div>
            <div class="form-row">
              <div class="form-field">
                <label>Delay (seconds)</label>
                <InputNumber v-model="editDelaySeconds" :min="0" class="full-width" />
              </div>
              <div class="form-field">
                <label>Sequence</label>
                <InputNumber v-model="editSequence" :min="1" class="full-width" />
              </div>
            </div>
            <div class="form-field">
              <label>Mode</label>
              <Select
                v-model="editMode"
                :options="modeOptions"
                optionLabel="label"
                optionValue="value"
                class="full-width"
              />
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
                <code>{{ subscription.code }}</code>
              </div>
              <div class="detail-item">
                <label>Name</label>
                <span>{{ subscription.name }}</span>
              </div>
              <div class="detail-item full-width" v-if="subscription.description">
                <label>Description</label>
                <span>{{ subscription.description }}</span>
              </div>
              <div class="detail-item">
                <label>Client Scope</label>
                <span>{{ getScopeLabel(subscription) }}</span>
              </div>
              <div class="detail-item">
                <label>Source</label>
                <span>{{ subscription.source }}</span>
              </div>
              <div class="detail-item full-width">
                <label>Endpoint</label>
                <code class="endpoint-url">{{ subscription.endpoint }}</code>
              </div>
              <div class="detail-item full-width" v-if="subscription.connectionId">
                <label>Connection</label>
                <code>{{ subscription.connectionId }}</code>
              </div>
              <div class="detail-item">
                <label>Queue</label>
                <code>{{ subscription.queue }}</code>
              </div>
              <div class="detail-item">
                <label>Dispatch Pool</label>
                <code>{{ subscription.dispatchPoolCode }}</code>
              </div>
              <div class="detail-item">
                <label>Mode</label>
                <span>{{ getModeLabel(subscription.mode) }}</span>
              </div>
              <div class="detail-item">
                <label>Max Age</label>
                <span>{{ subscription.maxAgeSeconds }} seconds</span>
              </div>
              <div class="detail-item">
                <label>Delay</label>
                <span>{{ subscription.delaySeconds }} seconds</span>
              </div>
              <div class="detail-item">
                <label>Timeout</label>
                <span>{{ subscription.timeoutSeconds }} seconds</span>
              </div>
              <div class="detail-item">
                <label>Sequence</label>
                <span>{{ subscription.sequence }}</span>
              </div>
              <div class="detail-item">
                <label>Status</label>
                <Tag
                  :value="subscription.status"
                  :severity="getStatusSeverity(subscription.status)"
                />
              </div>
              <div class="detail-item">
                <label>Created</label>
                <span>{{ formatDate(subscription.createdAt) }}</span>
              </div>
              <div class="detail-item">
                <label>Updated</label>
                <span>{{ formatDate(subscription.updatedAt) }}</span>
              </div>
            </div>
          </template>
        </div>
      </div>

      <!-- Event Types Card -->
      <div class="section-card">
        <div class="card-header">
          <h3>Event Types ({{ subscription.eventTypes?.length || 0 }})</h3>
        </div>
        <div class="card-content">
          <DataTable
            :value="subscription.eventTypes"
            stripedRows
            emptyMessage="No event types configured"
          >
            <Column field="eventTypeCode" header="Event Type Code" />
            <Column field="specVersion" header="Spec Version" />
          </DataTable>
        </div>
      </div>

      <!-- Actions Card -->
      <div class="section-card">
        <div class="card-header">
          <h3>Actions</h3>
        </div>
        <div class="card-content">
          <div class="action-items">
            <div v-if="subscription.status === 'ACTIVE'" class="action-item">
              <div class="action-info">
                <strong>Pause Subscription</strong>
                <p>Stop creating dispatch jobs for this subscription.</p>
              </div>
              <Button
                label="Pause"
                icon="pi pi-pause"
                severity="warn"
                outlined
                @click="confirmPause"
              />
            </div>

            <div v-if="subscription.status === 'PAUSED'" class="action-item">
              <div class="action-info">
                <strong>Resume Subscription</strong>
                <p>Re-enable dispatch job creation.</p>
              </div>
              <Button
                label="Resume"
                icon="pi pi-play"
                severity="success"
                outlined
                @click="confirmResume"
              />
            </div>

            <div class="action-item">
              <div class="action-info">
                <strong>Delete Subscription</strong>
                <p>Permanently delete this subscription. Cannot be undone.</p>
              </div>
              <Button
                label="Delete"
                icon="pi pi-trash"
                severity="danger"
                outlined
                @click="confirmDelete"
              />
            </div>
          </div>
        </div>
      </div>
    </template>

    <Message v-else severity="error">Subscription not found</Message>
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

.sub-code {
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

.endpoint-url {
  font-size: 13px;
  word-break: break-all;
}

.form-field {
  margin-bottom: 20px;
}

.form-field label {
  display: block;
  margin-bottom: 6px;
  font-weight: 500;
}

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
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

  .form-row {
    grid-template-columns: 1fr;
  }
}
</style>
