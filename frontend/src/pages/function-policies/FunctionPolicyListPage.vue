<script setup lang="ts">
// Function policies list (docs/spec/function-ui.md §2.4), anchor-only.
//
// GET /api/function-policies/{owner} is a per-owner read with no batch/list
// route on the wire — the spec's "List: owner, signers, limit ceilings,
// updated" implies a listing that does not exist as a single call. This
// page builds one by fanning out a getPolicy call per known owner
// (platform + every client); `PolicyResponse` also carries no `updated`
// timestamp, so that column is omitted. Both are noted in the H3 report as
// spec/API-document mismatches.
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { functionsApi, type PolicyResponse } from "@/api/functions";
import { clientsApi } from "@/api/clients";

const router = useRouter();
const route = useRoute();

interface Row {
	owner: string;
	ownerLabel: string;
	policy: PolicyResponse;
}

const rows = ref<Row[]>([]);
const loading = ref(true);

async function load() {
	loading.value = true;
	try {
		const clients = (await clientsApi.list()).clients;
		const owners = [
			{ id: "platform", label: "Platform" },
			...clients.map((c) => ({ id: c.id, label: c.name })),
		];
		const policies = await Promise.all(
			owners.map((o) => functionsApi.getPolicy(o.id).catch(() => null)),
		);
		rows.value = owners
			.map((o, i) => ({ owner: o.id, ownerLabel: o.label, policy: policies[i] }))
			.filter((r): r is Row => r.policy !== null && r.policy !== undefined);
	} catch (err) {
		console.error("Failed to load function policies", err);
		rows.value = [];
	} finally {
		loading.value = false;
	}
}

onMounted(load);

function viewPolicy(row: Row) {
	void router.push({
		path: `/function-policies/${encodeURIComponent(row.owner)}`,
		query: route.query,
	});
}

function onRowClick(event: { data: Row }) {
	viewPolicy(event.data);
}

function signerLines(policy: PolicyResponse): string {
	if (policy.signers.length === 0) return "—";
	return policy.signers.map((s) => `${s.issuer} / ${s.subject}`).join("\n");
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Function Policies</h1>
        <p class="page-subtitle">
          Per-owner signer allow-list and limit ceilings for publishing functions
        </p>
      </div>
    </header>

    <div class="fc-card table-card">
      <DataTable
        :value="rows"
        :loading="loading"
        data-key="owner"
        row-hover
        :rowClass="() => 'clickable-row'"
        stripedRows
        @row-click="onRowClick"
      >
        <template #empty>No owners found</template>

        <Column header="Owner">
          <template #body="{ data }">{{ data.ownerLabel }}</template>
        </Column>
        <Column header="Signers">
          <template #body="{ data }">
            <pre class="signer-lines">{{ signerLines(data.policy) }}</pre>
          </template>
        </Column>
        <Column header="Duration Ceiling">
          <template #body="{ data }">{{ data.policy.ceilings.maxDurationMs }} ms</template>
        </Column>
        <Column header="Concurrency Ceiling">
          <template #body="{ data }">{{ data.policy.ceilings.maxConcurrency }}</template>
        </Column>
        <Column header="">
          <template #body="{ data }">
            <Tag
              :value="data.policy.stored ? 'custom' : 'platform defaults'"
              :severity="data.policy.stored ? 'info' : 'secondary'"
            />
          </template>
        </Column>
      </DataTable>
    </div>

    <RouterView v-slot="{ Component }">
      <component :is="Component" @changed="load" />
    </RouterView>
  </div>
</template>

<style scoped>
.table-card {
  padding: 0;
  overflow: hidden;
}

.signer-lines {
  margin: 0;
  font-size: 12px;
  white-space: pre-wrap;
}
</style>
