<script setup lang="ts">
// Public routes tab (docs/spec/function-ui.md §2.1): the live manifest's
// `public[]` entries, joined with each hostname's verification state from
// GET /api/function-domains. Uses the materialised routes
// (GET /api/function-routes?address=...) rather than re-parsing the live
// version's manifest — promote reconciles fn_routes to exactly match
// public[], so the two are equivalent for a promoted function, and this
// avoids a second manifest fetch already paid for by the Versions tab.
import { computed, ref, watch } from "vue";
import { useAuthStore } from "@/stores/auth";
import { userHasPermission } from "@/stores/permissions";
import {
	functionsApi,
	type DomainResponse,
	type FunctionRouteResponse,
} from "@/api/functions";

const props = defineProps<{
	address: string;
	clientId?: string;
	hasLiveVersion: boolean;
}>();

const authStore = useAuthStore();
const canManageDomains = computed(() =>
	userHasPermission(authStore.user, "platform:function:domain:manage"),
);

const routes = ref<FunctionRouteResponse[]>([]);
const loading = ref(true);
const domainByHostname = ref<Map<string, DomainResponse>>(new Map());

watch(
	() => [props.address, props.clientId, props.hasLiveVersion] as const,
	async ([addr]) => {
		if (!addr) return;
		await load(addr);
	},
	{ immediate: true },
);

async function load(addr: string) {
	loading.value = true;
	try {
		const [routeResult, domainResult] = await Promise.all([
			functionsApi.listRoutes({ address: addr }),
			functionsApi.listDomains(props.clientId ?? "platform").catch(() => []),
		]);
		routes.value = routeResult;
		domainByHostname.value = new Map(domainResult.map((d) => [d.hostname, d]));
	} catch {
		routes.value = [];
	} finally {
		loading.value = false;
	}
}

function isLocalhost(hostname: string): boolean {
	return hostname === "localhost" || hostname.endsWith(".localhost");
}

function verificationLabel(hostname: string): string {
	if (isLocalhost(hostname)) return "auto-verified (dev mode)";
	const domain = domainByHostname.value.get(hostname);
	if (!domain) return "not claimed";
	return domain.verification.state === "VERIFIED" ? "verified" : "pending";
}

function verificationSeverity(hostname: string): "success" | "warn" | "danger" {
	if (isLocalhost(hostname)) return "success";
	const domain = domainByHostname.value.get(hostname);
	if (!domain) return "danger";
	return domain.verification.state === "VERIFIED" ? "success" : "warn";
}
</script>

<template>
  <div class="public-routes-tab">
    <p v-if="!hasLiveVersion" class="empty-hint">
      This function has no live version yet — public routes are created on promote.
    </p>
    <ProgressSpinner v-else-if="loading" style="width: 24px; height: 24px" />
    <p v-else-if="routes.length === 0" class="empty-hint">
      The live manifest declares no public routes.
    </p>
    <table v-else class="routes-table">
      <thead>
        <tr>
          <th>Hostname</th>
          <th>Path Prefix</th>
          <th>Verification</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="route in routes" :key="`${route.hostname}${route.pathPrefix}`">
          <td><code>{{ route.hostname }}</code></td>
          <td><code>{{ route.pathPrefix }}</code></td>
          <td>
            <Tag
              :value="verificationLabel(route.hostname)"
              :severity="verificationSeverity(route.hostname)"
            />
          </td>
          <td>
            <RouterLink
              v-if="canManageDomains"
              :to="{
                path: `/function-domains/${encodeURIComponent(route.hostname)}`,
                query: { owner: props.clientId ?? 'platform' },
              }"
            >
              View domain
            </RouterLink>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.empty-hint {
  color: #64748b;
  font-size: 13px;
}

.routes-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.routes-table th {
  text-align: left;
  padding: 8px 12px;
  color: #64748b;
  font-weight: 600;
  border-bottom: 1px solid #e2e8f0;
}

.routes-table td {
  padding: 8px 12px;
  border-bottom: 1px solid #f1f5f9;
}
</style>
