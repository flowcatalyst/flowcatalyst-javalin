<script setup lang="ts">
// Function domains list (docs/spec/function-ui.md §2.3). Replaces the H1/H2
// placeholder page.
import { ref, computed, onMounted, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { functionsApi, type DomainResponse } from "@/api/functions";
import { clientsApi, type Client } from "@/api/clients";
import { useAuthStore } from "@/stores/auth";
import { userScope, userHasPermission } from "@/stores/permissions";

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();

const isAnchor = computed(() => userScope(authStore.user) === "anchor");
const canManage = computed(() =>
	userHasPermission(authStore.user, "platform:function:domain:manage"),
);

const clients = ref<Client[]>([]);
// GET /api/function-domains is owner-scoped (clientId is a required query
// param, "platform" for platform-owned) — there is no "every domain I can
// see" listing, so an anchor picks an owner to view.
const ownerFilter = ref<string>(
	isAnchor.value ? "platform" : (authStore.user?.clientId ?? "platform"),
);

const ownerOptions = computed(() => [
	{ label: "Platform", value: "platform" },
	...clients.value.map((c) => ({ label: c.name, value: c.id })),
]);

const domains = ref<DomainResponse[]>([]);
const loading = ref(false);

async function load() {
	loading.value = true;
	try {
		domains.value = await functionsApi.listDomains(ownerFilter.value);
	} catch (err) {
		console.error("Failed to load function domains", err);
		domains.value = [];
	} finally {
		loading.value = false;
	}
}

watch(ownerFilter, load);

onMounted(async () => {
	if (isAnchor.value) {
		try {
			clients.value = (await clientsApi.list()).clients;
		} catch (err) {
			console.error("Failed to load clients", err);
		}
	}
	await load();
});

function viewDomain(d: DomainResponse) {
	void router.push({
		path: `/function-domains/${encodeURIComponent(d.hostname)}`,
		query: route.query,
	});
}

function onRowClick(event: { data: DomainResponse }) {
	viewDomain(event.data);
}

function claimDomain() {
	void router.push({ path: "/function-domains/new", query: route.query });
}

function formatDate(s?: string): string {
	if (!s) return "—";
	return new Date(s).toLocaleString();
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Function Domains</h1>
        <p class="page-subtitle">
          Zones claimed for functions' public routes — a claim covers every hostname under it and is
          usable immediately
        </p>
      </div>
      <Button
        v-if="canManage"
        label="Claim Domain"
        icon="pi pi-plus"
        @click="claimDomain"
      />
    </header>

    <div v-if="isAnchor" class="fc-card owner-filter-card">
      <FcFormField label="Owner">
        <template #default="{ id: fieldId }">
          <Select
            :id="fieldId"
            v-model="ownerFilter"
            :options="ownerOptions"
            optionLabel="label"
            optionValue="value"
            appendTo="self"
          />
        </template>
      </FcFormField>
    </div>

    <div class="fc-card table-card">
      <DataTable
        :value="domains"
        :loading="loading"
        data-key="id"
        row-hover
        :rowClass="() => 'clickable-row'"
        stripedRows
        @row-click="onRowClick"
      >
        <template #empty>No domains claimed for this owner</template>

        <Column header="Domain">
          <template #body="{ data }">
            <code>{{ data.hostname }}</code>
          </template>
        </Column>
        <Column header="Owner">
          <template #body="{ data }">{{ data.owner }}</template>
        </Column>
        <Column header="Claimed">
          <template #body="{ data }">{{ formatDate(data.createdAt) }}</template>
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

.owner-filter-card {
  margin-bottom: 16px;
  padding: 12px 20px;
  max-width: 320px;
}
</style>
