<script setup lang="ts">
// Function policies list (docs/spec/function-ui.md §2.4), anchor-only.
//
// `GET /api/function-policies` (S2) returns every STORED policy row in one
// call; owners with no row are not listed there and get a "defaults" row
// built from ONE per-owner `getPolicy` read (the effective-default shape is
// the same for every such owner).
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
		const [clients, listResponse] = await Promise.all([
			clientsApi.list(),
			functionsApi.listPolicies(),
		]);
		const owners = [
			{ id: "platform", label: "Platform" },
			...clients.clients.map((c) => ({ id: c.id, label: c.name })),
		];
		const stored = new Map(listResponse.policies.map((p) => [p.owner, p]));
		// The effective-default shape is the same for every owner without a
		// row (the platform's limit defaults; only `owner` differs), so one
		// read serves all of them — no per-owner fan-out.
		const firstMissing = owners.find((o) => !stored.has(o.id));
		const defaults = firstMissing
			? await functionsApi.getPolicy(firstMissing.id).catch(() => null)
			: null;
		rows.value = owners
			.map((o) => {
				const policy =
					stored.get(o.id) ?? (defaults ? { ...defaults, owner: o.id } : undefined);
				return policy ? { owner: o.id, ownerLabel: o.label, policy } : null;
			})
			.filter((r): r is Row => r !== null);
	} catch (err) {
		console.error("Failed to load function policies", err);
		rows.value = [];
	} finally {
		loading.value = false;
	}
}

onMounted(load);

function formatDate(dateString?: string | null): string {
	if (!dateString) return "—";
	return new Date(dateString).toLocaleString();
}

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
        <Column header="Updated">
          <template #body="{ data }">{{ formatDate(data.policy.updatedAt) }}</template>
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
