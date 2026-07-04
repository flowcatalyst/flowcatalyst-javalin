<script setup lang="ts">
import { ref, watch } from "vue";
import { useRouter } from "vue-router";
import { useConfirm } from "primevue/useconfirm";
import { toast } from "@/utils/errorBus";
import {
	scheduledJobsApi,
	type ScheduledJob,
	type ScheduledJobInstance,
} from "@/api/scheduled-jobs";
import EntityDrawer from "@/components/drawer/EntityDrawer.vue";
import { useDrawerRoute } from "@/composables/useDrawerRoute";

const emit = defineEmits<{
	changed: [];
}>();

const router = useRouter();
const confirm = useConfirm();

const drawer = ref<InstanceType<typeof EntityDrawer> | null>(null);
const { id, goToList } = useDrawerRoute({ listPath: "/scheduled-jobs" });

const loading = ref(true);
const loadError = ref<string | null>(null);
const job = ref<ScheduledJob | null>(null);
const recentInstances = ref<ScheduledJobInstance[]>([]);
const acting = ref(false);

// Reactive param: the drawer instance is reused when switching between rows.
watch(
	id,
	async (value) => {
		if (!value) return;
		job.value = null;
		recentInstances.value = [];
		await load(value);
	},
	{ immediate: true },
);

async function load(jobId: string) {
	loading.value = true;
	loadError.value = null;
	try {
		job.value = await scheduledJobsApi.get(jobId);
		const result = await scheduledJobsApi.listInstances(jobId, {
			page: 0,
			size: 10,
		});
		recentInstances.value = result.data;
	} catch {
		job.value = null;
		loadError.value = "Scheduled job not found";
	} finally {
		loading.value = false;
	}
}

function jobStatusSeverity(s: string): string {
	return s === "ACTIVE" ? "success" : s === "PAUSED" ? "warn" : "secondary";
}
function instanceStatusSeverity(s: string): string {
	switch (s) {
		case "DELIVERED":
		case "COMPLETED":
			return "success";
		case "QUEUED":
		case "IN_FLIGHT":
			return "info";
		case "FAILED":
		case "DELIVERY_FAILED":
			return "danger";
		default:
			return "secondary";
	}
}
function fmt(s?: string): string {
	return s ? new Date(s).toLocaleString() : "—";
}

async function pause() {
	if (!job.value) return;
	const jobId = job.value.id;
	acting.value = true;
	try {
		await scheduledJobsApi.pause(jobId);
		toast.success("Paused", "Scheduled job paused");
		emit("changed");
		await load(jobId);
	} finally {
		acting.value = false;
	}
}

async function resume() {
	if (!job.value) return;
	const jobId = job.value.id;
	acting.value = true;
	try {
		await scheduledJobsApi.resume(jobId);
		toast.success("Resumed", "Scheduled job resumed");
		emit("changed");
		await load(jobId);
	} finally {
		acting.value = false;
	}
}

async function fireNow() {
	if (!job.value) return;
	const jobId = job.value.id;
	acting.value = true;
	try {
		const result = await scheduledJobsApi.fire(jobId);
		toast.success("Fired", `Instance ${result.id} created`);
		emit("changed");
		await load(jobId);
	} finally {
		acting.value = false;
	}
}

function archive() {
	if (!job.value) return;
	confirm.require({
		message:
			"Archive this scheduled job? It will stop firing but its history is preserved.",
		header: "Confirm Archive",
		icon: "pi pi-exclamation-triangle",
		accept: async () => {
			acting.value = true;
			try {
				await scheduledJobsApi.archive(job.value!.id);
				toast.success("Archived", "Scheduled job archived");
				emit("changed");
				void drawer.value?.close(true);
			} finally {
				acting.value = false;
			}
		},
	});
}

function deleteJob() {
	if (!job.value) return;
	confirm.require({
		message:
			"DELETE this scheduled job? Definition is removed permanently. Instance history is retained until partition retention drops it.",
		header: "Confirm Delete",
		icon: "pi pi-exclamation-triangle",
		acceptClass: "p-button-danger",
		accept: async () => {
			acting.value = true;
			try {
				await scheduledJobsApi.delete(job.value!.id);
				toast.success("Deleted", "Scheduled job deleted");
				emit("changed");
				void drawer.value?.close(true);
			} finally {
				acting.value = false;
			}
		},
	});
}

// Instances are full pages, not drawers — navigate away from the list+drawer.
function viewAllInstances() {
	if (!id.value) return;
	void router.push(`/scheduled-jobs/${id.value}/instances`);
}

function onRecentRowClick(event: { data: ScheduledJobInstance }) {
	void router.push(`/scheduled-jobs/instances/${event.data.id}`);
}
</script>

<template>
  <EntityDrawer
    ref="drawer"
    :title="job?.name || 'Scheduled Job'"
    :subtitle="job?.code"
    size="wide"
    :loading="loading"
    :error="loadError"
    @close="goToList()"
  >
    <template v-if="job" #header-extra>
      <Tag :value="job.status" :severity="jobStatusSeverity(job.status)" />
      <Tag v-if="job.hasActiveInstance" value="Running" severity="warn" />
    </template>

    <template v-if="job">
      <!-- Definition -->
      <FcFormSection title="Definition" flat>
        <div class="fc-detail-grid">
          <FcDetailField label="Code">
            <code>{{ job.code }}</code>
          </FcDetailField>
          <FcDetailField label="Scope" :value="job.clientName ?? job.clientId ?? 'Platform'" />
          <FcDetailField label="Timezone" :value="job.timezone" />
          <FcDetailField label="Last Fired" :value="fmt(job.lastFiredAt)" />
          <FcDetailField label="Created" :value="fmt(job.createdAt)" />
          <FcDetailField label="Updated" :value="fmt(job.updatedAt)" />
          <FcDetailField label="Delivery Max Attempts" :value="job.deliveryMaxAttempts" />
          <FcDetailField label="Timeout Seconds" :value="job.timeoutSeconds" />
          <FcDetailField label="Concurrent" :value="job.concurrent ? 'Yes' : 'No'" />
          <FcDetailField label="Tracks Completion" :value="job.tracksCompletion ? 'Yes' : 'No'" />
          <FcDetailField label="Target URL" span>
            <code v-if="job.targetUrl" class="target-url">{{ job.targetUrl }}</code>
            <span v-else class="missing-target">— not configured (firings will fail)</span>
          </FcDetailField>
          <FcDetailField label="Cron Expressions" span>
            <ul class="cron-list">
              <li v-for="(c, i) in job.crons" :key="i"><code>{{ c }}</code></li>
            </ul>
          </FcDetailField>
          <FcDetailField
            v-if="job.description"
            label="Description"
            :value="job.description"
            span
          />
          <FcDetailField v-if="job.payload" label="Payload" span>
            <pre class="payload-pre">{{ JSON.stringify(job.payload, null, 2) }}</pre>
          </FcDetailField>
        </div>
      </FcFormSection>

      <!-- Recent Firings -->
      <FcFormSection title="Recent Firings" flat>
        <template #actions>
          <Button
            label="View all"
            icon="pi pi-arrow-right"
            icon-pos="right"
            text
            @click="viewAllInstances"
          />
        </template>
        <DataTable
          :value="recentInstances"
          row-hover
          selection-mode="single"
          @row-click="onRecentRowClick"
        >
          <Column header="Fired At">
            <template #body="{ data }">{{ fmt(data.firedAt) }}</template>
          </Column>
          <Column header="Trigger" field="triggerKind" />
          <Column header="Status">
            <template #body="{ data }">
              <Tag :value="data.status" :severity="instanceStatusSeverity(data.status)" />
            </template>
          </Column>
          <Column header="Attempts" field="deliveryAttempts" />
          <Column header="Delivered">
            <template #body="{ data }">{{ fmt(data.deliveredAt) }}</template>
          </Column>
          <Column header="Completed">
            <template #body="{ data }">{{ fmt(data.completedAt) }}</template>
          </Column>
          <template #empty>
            <div class="empty-message">No firings yet.</div>
          </template>
        </DataTable>
      </FcFormSection>

      <!-- Actions -->
      <FcFormSection title="Actions" flat>
        <div class="action-buttons">
          <Button
            v-if="job.status === 'ACTIVE'"
            label="Pause"
            icon="pi pi-pause"
            severity="warn"
            :loading="acting"
            @click="pause"
          />
          <Button
            v-if="job.status === 'PAUSED'"
            label="Resume"
            icon="pi pi-play"
            severity="success"
            :loading="acting"
            @click="resume"
          />
          <Button
            label="Fire Now"
            icon="pi pi-bolt"
            :disabled="job.status === 'ARCHIVED'"
            :loading="acting"
            @click="fireNow"
          />
          <Button
            v-if="job.status !== 'ARCHIVED'"
            label="Archive"
            icon="pi pi-inbox"
            severity="secondary"
            :loading="acting"
            @click="archive"
          />
          <Button
            label="Delete"
            icon="pi pi-trash"
            severity="danger"
            text
            :loading="acting"
            @click="deleteJob"
          />
        </div>
      </FcFormSection>
    </template>
  </EntityDrawer>
</template>

<style scoped>
.target-url {
  font-size: 13px;
  word-break: break-all;
}

.missing-target {
  color: #f97316;
}

.cron-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
}

.payload-pre {
  margin: 0;
  padding: 8px;
  font-size: 12px;
  background: #f8fafc;
  border-radius: 6px;
  overflow-x: auto;
}

.action-buttons {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.empty-message {
  text-align: center;
  padding: 16px;
  color: #64748b;
}
</style>
