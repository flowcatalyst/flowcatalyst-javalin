<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import {
	scheduledJobsApi,
	type ScheduledJobInstance,
	type ScheduledJobInstanceLog,
} from "@/api/scheduled-jobs";

const route = useRoute();
const instanceId = String(route.params["instanceId"]);

const inst = ref<ScheduledJobInstance | null>(null);
const logs = ref<ScheduledJobInstanceLog[]>([]);
const loading = ref(false);

async function load() {
	loading.value = true;
	try {
		const [i, l] = await Promise.all([
			scheduledJobsApi.getInstance(instanceId),
			scheduledJobsApi.listInstanceLogs(instanceId),
		]);
		inst.value = i;
		logs.value = l;
	} catch (err) {
		console.error("Failed to load instance", err);
	} finally {
		loading.value = false;
	}
}

onMounted(load);

function statusSeverity(s: string): string {
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
function logLevelSeverity(l: string): string {
	switch (l) {
		case "ERROR":
			return "danger";
		case "WARN":
			return "warn";
		case "DEBUG":
			return "secondary";
		default:
			return "info";
	}
}
function fmt(s?: string): string {
	return s ? new Date(s).toLocaleString() : "—";
}
</script>

<template>
	<div v-if="loading && !inst" class="card">Loading…</div>

	<div v-else-if="inst" class="space-y-4">
		<div class="card">
			<div class="text-sm text-gray-500">
				<router-link to="/scheduled-jobs" class="hover:underline">Scheduled Jobs</router-link>
				/ <router-link :to="`/scheduled-jobs/${inst.scheduledJobId}`" class="hover:underline">{{ inst.jobCode }}</router-link>
				/ Instance
			</div>
			<h2 class="mt-1">Firing {{ inst.id }}</h2>
			<div class="flex gap-2 mt-2">
				<Tag :value="inst.status" :severity="statusSeverity(inst.status)" />
				<Tag :value="inst.triggerKind" severity="secondary" />
				<Tag v-if="inst.completionStatus" :value="inst.completionStatus" :severity="inst.completionStatus === 'SUCCESS' ? 'success' : 'danger'" />
			</div>

			<div class="grid grid-cols-1 md:grid-cols-2 gap-3 mt-4 text-sm">
				<div><strong>Fired At:</strong> {{ fmt(inst.firedAt) }}</div>
				<div><strong>Scheduled For:</strong> {{ fmt(inst.scheduledFor) }}</div>
				<div><strong>Delivered At:</strong> {{ fmt(inst.deliveredAt) }}</div>
				<div><strong>Completed At:</strong> {{ fmt(inst.completedAt) }}</div>
				<div><strong>Delivery Attempts:</strong> {{ inst.deliveryAttempts }}</div>
				<div><strong>Correlation Id:</strong> {{ inst.correlationId ?? "—" }}</div>
				<div v-if="inst.deliveryError" class="md:col-span-2">
					<strong>Delivery Error:</strong>
					<pre class="text-xs bg-red-50 dark:bg-red-950 text-red-600 p-2 rounded mt-1">{{ inst.deliveryError }}</pre>
				</div>
				<div v-if="inst.completionResult" class="md:col-span-2">
					<strong>Completion Result:</strong>
					<pre class="text-xs bg-gray-100 dark:bg-gray-800 p-2 rounded mt-1">{{ JSON.stringify(inst.completionResult, null, 2) }}</pre>
				</div>
			</div>
		</div>

		<div class="card">
			<h3>Logs</h3>
			<div v-if="logs.length === 0" class="text-gray-500 mt-2">No log entries.</div>
			<DataTable v-else :value="logs" class="mt-3">
				<Column header="Time" style="width: 18%">
					<template #body="{ data }">
						<span class="text-xs">{{ fmt(data.createdAt) }}</span>
					</template>
				</Column>
				<Column header="Level" style="width: 8%">
					<template #body="{ data }">
						<Tag :value="data.level" :severity="logLevelSeverity(data.level)" />
					</template>
				</Column>
				<Column header="Message" field="message" />
				<Column header="Metadata" style="width: 30%">
					<template #body="{ data }">
						<pre v-if="data.metadata" class="text-xs">{{ JSON.stringify(data.metadata) }}</pre>
					</template>
				</Column>
			</DataTable>
		</div>
	</div>
</template>
