<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import { useConfirm } from "primevue/useconfirm";
import { useReturnTo } from "@/composables/useReturnTo";
import { toast } from "@/utils/errorBus";
import {
	scheduledJobsApi,
	type ScheduledJob,
	type ScheduledJobInstance,
} from "@/api/scheduled-jobs";

const route = useRoute();
const confirm = useConfirm();
const { returnTo, forwardFrom } = useReturnTo();

const id = String(route.params["id"]);
const job = ref<ScheduledJob | null>(null);
const recentInstances = ref<ScheduledJobInstance[]>([]);
const loading = ref(false);
const acting = ref(false);

async function load() {
	loading.value = true;
	try {
		job.value = await scheduledJobsApi.get(id);
		const result = await scheduledJobsApi.listInstances(id, { page: 0, size: 10 });
		recentInstances.value = result.data;
	} catch (err) {
		console.error("Failed to load scheduled job", err);
	} finally {
		loading.value = false;
	}
}

onMounted(load);

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
	acting.value = true;
	try {
		await scheduledJobsApi.pause(job.value.id);
		toast.success("Paused", "Scheduled job paused");
		await load();
	} finally { acting.value = false; }
}

async function resume() {
	if (!job.value) return;
	acting.value = true;
	try {
		await scheduledJobsApi.resume(job.value.id);
		toast.success("Resumed", "Scheduled job resumed");
		await load();
	} finally { acting.value = false; }
}

function archive() {
	if (!job.value) return;
	confirm.require({
		message: "Archive this scheduled job? It will stop firing but its history is preserved.",
		header: "Confirm Archive",
		icon: "pi pi-exclamation-triangle",
		accept: async () => {
			acting.value = true;
			try {
				await scheduledJobsApi.archive(job.value!.id);
				toast.success("Archived", "Scheduled job archived");
				await load();
			} finally { acting.value = false; }
		},
	});
}

function deleteJob() {
	if (!job.value) return;
	confirm.require({
		message: "DELETE this scheduled job? Definition is removed permanently. Instance history is retained until partition retention drops it.",
		header: "Confirm Delete",
		icon: "pi pi-exclamation-triangle",
		acceptClass: "p-button-danger",
		accept: async () => {
			acting.value = true;
			try {
				await scheduledJobsApi.delete(job.value!.id);
				toast.success("Deleted", "Scheduled job deleted");
				returnTo("/scheduled-jobs");
			} finally { acting.value = false; }
		},
	});
}

async function fireNow() {
	if (!job.value) return;
	acting.value = true;
	try {
		const result = await scheduledJobsApi.fire(job.value.id);
		toast.success("Fired", `Instance ${result.id} created`);
		await load();
	} finally { acting.value = false; }
}

function onRecentRowClick(event: { data: ScheduledJobInstance }) {
	forwardFrom(`/scheduled-jobs/instances/${event.data.id}`);
}
</script>

<template>
	<ConfirmDialog />
	<div v-if="loading && !job" class="card">Loading…</div>

	<div v-else-if="job" class="space-y-4">
		<!-- Header -->
		<div class="card">
			<div class="flex items-start justify-between gap-4">
				<div>
					<div class="text-sm text-gray-500">
						<a
							href="#"
							class="hover:underline"
							@click.prevent="returnTo('/scheduled-jobs')"
						>Scheduled Jobs</a>
						/ {{ job.code }}
					</div>
					<h2 class="mt-1">{{ job.name }}</h2>
					<div class="flex gap-2 mt-2">
						<Tag :value="job.status" :severity="jobStatusSeverity(job.status)" />
						<Tag v-if="job.concurrent" value="Concurrent" severity="info" />
						<Tag v-if="job.tracksCompletion" value="Tracks Completion" severity="info" />
						<Tag v-if="job.hasActiveInstance" value="Currently Running" severity="warn" />
					</div>
				</div>
				<div class="flex gap-2 flex-wrap justify-end">
					<Button v-if="job.status === 'ACTIVE'" label="Pause" icon="pi pi-pause" severity="warn" :loading="acting" @click="pause" />
					<Button v-if="job.status === 'PAUSED'" label="Resume" icon="pi pi-play" severity="success" :loading="acting" @click="resume" />
					<Button label="Fire Now" icon="pi pi-bolt" :disabled="job.status === 'ARCHIVED'" :loading="acting" @click="fireNow" />
					<Button v-if="job.status !== 'ARCHIVED'" label="Archive" icon="pi pi-inbox" severity="secondary" :loading="acting" @click="archive" />
					<Button label="Delete" icon="pi pi-trash" severity="danger" text :loading="acting" @click="deleteJob" />
				</div>
			</div>
		</div>

		<!-- Definition -->
		<div class="card">
			<h3>Definition</h3>
			<div class="grid grid-cols-1 md:grid-cols-2 gap-3 mt-3 text-sm">
				<div><strong>Code:</strong> <span class="font-mono">{{ job.code }}</span></div>
				<div><strong>Scope:</strong> {{ job.clientName ?? (job.clientId ?? "Platform") }}</div>
				<div><strong>Timezone:</strong> {{ job.timezone }}</div>
				<div><strong>Last Fired:</strong> {{ fmt(job.lastFiredAt) }}</div>
				<div><strong>Created:</strong> {{ fmt(job.createdAt) }}</div>
				<div><strong>Updated:</strong> {{ fmt(job.updatedAt) }}</div>
				<div><strong>Delivery Max Attempts:</strong> {{ job.deliveryMaxAttempts }}</div>
				<div><strong>Timeout Seconds:</strong> {{ job.timeoutSeconds ?? "—" }}</div>
				<div class="md:col-span-2">
					<strong>Target URL:</strong>
					<span v-if="job.targetUrl" class="font-mono text-xs">{{ job.targetUrl }}</span>
					<span v-else class="text-orange-500">— not configured (firings will fail)</span>
				</div>
				<div class="md:col-span-2">
					<strong>Cron Expressions:</strong>
					<ul class="list-disc list-inside font-mono text-xs mt-1">
						<li v-for="(c, i) in job.crons" :key="i">{{ c }}</li>
					</ul>
				</div>
				<div v-if="job.description" class="md:col-span-2">
					<strong>Description:</strong> {{ job.description }}
				</div>
				<div v-if="job.payload" class="md:col-span-2">
					<strong>Payload:</strong>
					<pre class="text-xs bg-gray-100 dark:bg-gray-800 p-2 rounded mt-1">{{ JSON.stringify(job.payload, null, 2) }}</pre>
				</div>
			</div>
		</div>

		<!-- Recent Instances -->
		<div class="card">
			<div class="flex justify-between items-center">
				<h3>Recent Firings</h3>
				<a
					href="#"
					class="text-sm text-blue-500 hover:underline"
					@click.prevent="forwardFrom(`/scheduled-jobs/${job.id}/instances`)"
				>
					View all →
				</a>
			</div>
			<DataTable
				:value="recentInstances"
				row-hover
				class="mt-3"
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
					<div class="text-center text-gray-500 py-4">No firings yet.</div>
				</template>
			</DataTable>
		</div>
	</div>
</template>
