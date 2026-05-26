<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, onMounted } from "vue";
import { useRoute } from "vue-router";
import { useConfirm } from "primevue/useconfirm";
import {
	dispatchPoolsApi,
	type DispatchPool,
	type DispatchPoolStatus,
} from "@/api/dispatch-pools";
import { useReturnTo } from "@/composables/useReturnTo";

const route = useRoute();
const { returnTo } = useReturnTo();
const confirm = useConfirm();

const loading = ref(true);
const pool = ref<DispatchPool | null>(null);
const editing = ref(false);
const saving = ref(false);

// Edit form
const editName = ref("");
const editDescription = ref("");
const editRateLimit = ref<number | null>(null);
const editConcurrency = ref<number | null>(null);

onMounted(async () => {
	const id = route.params['id'] as string;
	if (id) {
		await loadPool(id);
	}
});

async function loadPool(id: string) {
	loading.value = true;
	try {
		pool.value = await dispatchPoolsApi.get(id);
	} catch {
		pool.value = null;
	} finally {
		loading.value = false;
	}
}

function startEditing() {
	if (pool.value) {
		editName.value = pool.value.name;
		editDescription.value = pool.value.description || "";
		editRateLimit.value = pool.value.rateLimit;
		editConcurrency.value = pool.value.concurrency;
		editing.value = true;
	}
}

function cancelEditing() {
	editing.value = false;
}

async function saveChanges() {
	if (!pool.value) return;

	saving.value = true;
	const id = pool.value.id;
	try {
		await dispatchPoolsApi.update(id, {
			name: editName.value,
			description: editDescription.value || undefined,
			rateLimit: editRateLimit.value || undefined,
			concurrency: editConcurrency.value || undefined,
		});
		await loadPool(id);
		editing.value = false;
		toast.success("Success", "Pool updated");
	} catch {
	} finally {
		saving.value = false;
	}
}

function confirmActivate() {
	confirm.require({
		message: "Activate this dispatch pool?",
		header: "Activate Pool",
		icon: "pi pi-check-circle",
		acceptLabel: "Activate",
		accept: activatePool,
	});
}

async function activatePool() {
	if (!pool.value) return;
	try {
		await dispatchPoolsApi.activate(pool.value.id);
		pool.value = await dispatchPoolsApi.get(pool.value.id);
		toast.success("Success", "Pool activated");
	} catch {
	}
}

function confirmSuspend() {
	confirm.require({
		message: "Suspend this dispatch pool? Jobs will not be processed.",
		header: "Suspend Pool",
		icon: "pi pi-exclamation-triangle",
		acceptLabel: "Suspend",
		acceptClass: "p-button-warning",
		accept: suspendPool,
	});
}

async function suspendPool() {
	if (!pool.value) return;
	try {
		await dispatchPoolsApi.suspend(pool.value.id);
		pool.value = await dispatchPoolsApi.get(pool.value.id);
		toast.success("Success", "Pool suspended");
	} catch {
	}
}

function confirmDelete() {
	confirm.require({
		message: "Delete this dispatch pool? This action will archive it.",
		header: "Delete Pool",
		icon: "pi pi-exclamation-triangle",
		acceptLabel: "Delete",
		acceptClass: "p-button-danger",
		accept: deletePool,
	});
}

async function deletePool() {
	if (!pool.value) return;
	try {
		await dispatchPoolsApi.delete(pool.value.id);
		toast.success("Success", "Pool deleted");
		returnTo("/dispatch-pools");
	} catch {
	}
}

function getStatusSeverity(status: DispatchPoolStatus) {
	switch (status) {
		case "ACTIVE":
			return "success";
		case "SUSPENDED":
			return "warn";
		case "ARCHIVED":
			return "secondary";
		default:
			return "secondary";
	}
}

function formatDate(dateString: string) {
	return new Date(dateString).toLocaleString();
}

function getScopeLabel(p: DispatchPool) {
	if (p.clientIdentifier) {
		return p.clientIdentifier;
	}
	return "Anchor-level (no client)";
}
</script>

<template>
  <div class="page-container">
    <div v-if="loading" class="loading-container">
      <ProgressSpinner strokeWidth="3" />
    </div>

    <template v-else-if="pool">
      <!-- Header -->
      <header class="page-header">
        <div class="header-content">
          <Button
            icon="pi pi-arrow-left"
            text
            severity="secondary"
            @click="returnTo('/dispatch-pools')"
            v-tooltip="'Back to list'"
          />
          <div class="header-text">
            <h1 class="page-title">{{ pool.name }}</h1>
            <code class="pool-code">{{ pool.code }}</code>
          </div>
          <Tag :value="pool.status" :severity="getStatusSeverity(pool.status)" />
        </div>
      </header>

      <!-- Details Card -->
      <div class="section-card">
        <div class="card-header">
          <h3>Pool Details</h3>
          <Button
            v-if="!editing && pool.status !== 'ARCHIVED'"
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
              <Textarea v-model="editDescription" class="full-width" rows="3" />
            </div>
            <div class="form-row">
              <div class="form-field">
                <label>Rate Limit (per minute)</label>
                <InputNumber v-model="editRateLimit" :min="1" class="full-width" placeholder="Unlimited" />
                <small class="hint">Leave blank to run on concurrency only.</small>
              </div>
              <div class="form-field">
                <label>Concurrency</label>
                <InputNumber v-model="editConcurrency" :min="1" class="full-width" />
              </div>
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
                <code>{{ pool.code }}</code>
              </div>
              <div class="detail-item">
                <label>Name</label>
                <span>{{ pool.name }}</span>
              </div>
              <div class="detail-item full-width" v-if="pool.description">
                <label>Description</label>
                <span>{{ pool.description }}</span>
              </div>
              <div class="detail-item">
                <label>Rate Limit</label>
                <span v-if="pool.rateLimit != null">{{ pool.rateLimit }} / minute</span>
                <span v-else>Unlimited (concurrency-only)</span>
              </div>
              <div class="detail-item">
                <label>Concurrency</label>
                <span>{{ pool.concurrency }}</span>
              </div>
              <div class="detail-item">
                <label>Client Scope</label>
                <span>{{ getScopeLabel(pool) }}</span>
              </div>
              <div class="detail-item">
                <label>Status</label>
                <Tag :value="pool.status" :severity="getStatusSeverity(pool.status)" />
              </div>
              <div class="detail-item">
                <label>Created</label>
                <span>{{ formatDate(pool.createdAt) }}</span>
              </div>
              <div class="detail-item">
                <label>Updated</label>
                <span>{{ formatDate(pool.updatedAt) }}</span>
              </div>
            </div>
          </template>
        </div>
      </div>

      <!-- Actions Card -->
      <div class="section-card" v-if="pool.status !== 'ARCHIVED'">
        <div class="card-header">
          <h3>Actions</h3>
        </div>
        <div class="card-content">
          <div class="action-items">
            <div v-if="pool.status !== 'ACTIVE'" class="action-item">
              <div class="action-info">
                <strong>Activate Pool</strong>
                <p>Enable this pool for processing dispatch jobs.</p>
              </div>
              <Button label="Activate" severity="success" outlined @click="confirmActivate" />
            </div>

            <div v-if="pool.status === 'ACTIVE'" class="action-item">
              <div class="action-info">
                <strong>Suspend Pool</strong>
                <p>Temporarily stop processing jobs in this pool.</p>
              </div>
              <Button label="Suspend" severity="warn" outlined @click="confirmSuspend" />
            </div>

            <div class="action-item">
              <div class="action-info">
                <strong>Delete Pool</strong>
                <p>Archive this pool. Cannot be undone if there are active subscriptions.</p>
              </div>
              <Button label="Delete" severity="danger" outlined @click="confirmDelete" />
            </div>
          </div>
        </div>
      </div>
    </template>

    <Message v-else severity="error">Dispatch pool not found</Message>
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

.pool-code {
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
