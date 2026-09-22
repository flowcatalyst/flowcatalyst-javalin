<script setup lang="ts">
// The function detail drawer (docs/spec/function-ui.md §2.1). Overview +
// Versions + Config & secrets + Public routes + Invoke. The last four are
// their own components (FunctionVersionsTab / FunctionConfigSecretsTab /
// FunctionPublicRoutesTab / FunctionInvokeTab) so each can be unit-tested
// without mounting this whole drawer.
import { computed, ref, watch } from "vue";
import { toast } from "@/utils/errorBus";
import { useConfirm } from "primevue/useconfirm";
import {
	functionsApi,
	type FunctionResponse,
	type StatusResponse,
	type VersionResponse,
} from "@/api/functions";
import { clientsApi } from "@/api/clients";
import { useAuthStore } from "@/stores/auth";
import { userHasPermission } from "@/stores/permissions";
import EntityDrawer from "@/components/drawer/EntityDrawer.vue";
import { useDrawerRoute } from "@/composables/useDrawerRoute";
import { useDirtyForm } from "@/composables/useDirtyForm";
import FunctionVersionsTab from "./FunctionVersionsTab.vue";
import FunctionConfigSecretsTab from "./FunctionConfigSecretsTab.vue";
import FunctionPublicRoutesTab from "./FunctionPublicRoutesTab.vue";
import FunctionInvokeTab from "./FunctionInvokeTab.vue";

const emit = defineEmits<{
	changed: [];
}>();

const confirm = useConfirm();
const authStore = useAuthStore();

const canManage = computed(() =>
	userHasPermission(authStore.user, "platform:function:function:manage"),
);
const canInvoke = computed(() =>
	userHasPermission(authStore.user, "platform:function:version:invoke"),
);

const editing = ref(false);
const editDescription = ref("");

const { dirty, markClean, reset: resetDirty } = useDirtyForm(() => ({
	description: editDescription.value,
}));

const drawer = ref<InstanceType<typeof EntityDrawer> | null>(null);
const { id: address, goToList } = useDrawerRoute({
	listPath: "/functions",
	paramKey: "address",
	dirty: computed(() => editing.value && dirty.value),
});

const loading = ref(true);
const loadError = ref<string | null>(null);
const fn = ref<FunctionResponse | null>(null);
const ownerName = ref<string | null>(null);
const saving = ref(false);

const status = ref<StatusResponse | null>(null);
const statusLoading = ref(false);

const activeTab = ref("overview");
const invokeVersions = ref<VersionResponse[]>([]);
const invokeVersionsLoaded = ref(false);

watch(
	address,
	async (value) => {
		if (!value) return;
		editing.value = false;
		resetDirty();
		activeTab.value = "overview";
		invokeVersionsLoaded.value = false;
		invokeVersions.value = [];
		await loadFunction(value);
		void loadStatus(value);
	},
	{ immediate: true },
);

// Lazy: the version list backing the Invoke tab's selector is only fetched
// once that tab is actually opened.
watch(activeTab, async (tab) => {
	if (tab !== "invoke" || invokeVersionsLoaded.value || !fn.value) return;
	invokeVersionsLoaded.value = true;
	try {
		invokeVersions.value = await functionsApi.listVersions(fn.value.address);
	} catch {
		invokeVersions.value = [];
	}
});

/** Refresh after a Versions-tab promote/retire/publish — the live alias
 * (Overview) and the Hosts panel (status) can both have changed. */
async function onVersionsChanged() {
	if (!fn.value) return;
	await loadFunction(fn.value.address);
	void loadStatus(fn.value.address);
}

async function loadFunction(addr: string) {
	loading.value = true;
	loadError.value = null;
	ownerName.value = null;
	try {
		fn.value = await functionsApi.get(addr);
		if (fn.value.clientId) {
			try {
				const client = await clientsApi.get(fn.value.clientId);
				ownerName.value = client.name;
			} catch {
				// Fall back to the raw client id below.
			}
		}
	} catch {
		fn.value = null;
		loadError.value = "Function not found";
	} finally {
		loading.value = false;
	}
}

async function loadStatus(addr: string) {
	statusLoading.value = true;
	try {
		status.value = await functionsApi.status(addr);
	} catch {
		status.value = null;
	} finally {
		statusLoading.value = false;
	}
}

function ownerLabel(): string {
	if (!fn.value?.clientId) return "Platform";
	return ownerName.value ?? fn.value.clientId;
}

function startEditing() {
	if (!fn.value) return;
	editDescription.value = fn.value.description ?? "";
	editing.value = true;
	markClean();
}

function cancelEditing() {
	editing.value = false;
	resetDirty();
}

async function saveChanges() {
	if (!fn.value) return;
	saving.value = true;
	const addr = fn.value.address;
	try {
		await functionsApi.update(addr, { description: editDescription.value });
		await loadFunction(addr);
		editing.value = false;
		resetDirty();
		toast.success("Success", "Function updated");
		emit("changed");
	} catch {
		// update errors surface via the global error toast
	} finally {
		saving.value = false;
	}
}

function confirmDelete() {
	if (!fn.value) return;
	confirm.require({
		message:
			`Delete ${fn.value.address}? This permanently deletes the function and cascades to ` +
			"its versions, aliases, routes, trigger objects and stored artifacts. This cannot be undone.",
		header: "Delete Function",
		icon: "pi pi-exclamation-triangle",
		acceptLabel: "Delete",
		acceptClass: "p-button-danger",
		accept: deleteFunction,
	});
}

async function deleteFunction() {
	if (!fn.value) return;
	try {
		await functionsApi.delete(fn.value.address);
		toast.success("Success", "Function deleted");
		emit("changed");
		editing.value = false;
		void drawer.value?.close(true);
	} catch {
		// errors surface via the global error toast
	}
}

function statusSeverity(s: string): "success" | "secondary" {
	return s === "ACTIVE" ? "success" : "secondary";
}

function hostStateSeverity(s: string): "success" | "warn" {
	return s === "ACTIVE" ? "success" : "warn";
}

function loadedStateSeverity(s: string): "success" | "info" | "danger" {
	if (s === "LOADED") return "success";
	if (s === "FAILED") return "danger";
	return "info";
}

function formatDate(dateString?: string | null): string {
	if (!dateString) return "—";
	return new Date(dateString).toLocaleString();
}

// "how long ago" for a heartbeat timestamp, not just the absolute time.
function heartbeatAge(dateString?: string | null): string {
	if (!dateString) return "—";
	const ms = Date.now() - new Date(dateString).getTime();
	if (ms < 0) return "just now";
	const seconds = Math.floor(ms / 1000);
	if (seconds < 60) return `${seconds}s ago`;
	const minutes = Math.floor(seconds / 60);
	if (minutes < 60) return `${minutes}m ago`;
	const hours = Math.floor(minutes / 60);
	return `${hours}h ago`;
}
</script>

<template>
  <EntityDrawer
    size="two-thirds"
    ref="drawer"
    :title="fn?.name || fn?.address || 'Function'"
    :subtitle="fn?.address"
    :loading="loading"
    :error="loadError"
    :dirty="editing && dirty"
    @close="goToList()"
  >
    <template v-if="fn" #header-extra>
      <Tag :value="fn.status" :severity="statusSeverity(fn.status)" />
    </template>

    <template v-if="fn">
      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab value="overview">Overview</Tab>
          <Tab value="versions">Versions</Tab>
          <Tab value="config">Config &amp; Secrets</Tab>
          <Tab value="routes">Public Routes</Tab>
          <Tab v-if="canInvoke" value="invoke">Invoke</Tab>
        </TabList>
        <TabPanels>
          <TabPanel value="overview">
            <FcFormSection title="Function Details" flat>
              <template v-if="!editing && canManage" #actions>
                <Button icon="pi pi-pencil" label="Edit" text @click="startEditing" />
              </template>

              <template v-if="editing">
                <div class="fc-form-grid">
                  <FcFormField label="Description" span>
                    <template #default="{ id: fieldId }">
                      <Textarea :id="fieldId" v-model="editDescription" rows="3" />
                    </template>
                  </FcFormField>
                </div>
              </template>

              <template v-else>
                <div class="fc-detail-grid">
                  <FcDetailField label="Address">
                    <code>{{ fn.address }}</code>
                  </FcDetailField>
                  <FcDetailField label="Owner" :value="ownerLabel()" />
                  <FcDetailField label="Runtime" :value="fn.runtime" />
                  <FcDetailField label="Description" :value="fn.description" span />
                  <FcDetailField label="Live Version">
                    <span v-if="fn.live">v{{ fn.live.version }}</span>
                    <span v-else>— (no live version yet)</span>
                  </FcDetailField>
                  <FcDetailField label="Status">
                    <Tag :value="fn.status" :severity="statusSeverity(fn.status)" />
                  </FcDetailField>
                  <FcDetailField label="Created" :value="formatDate(fn.createdAt)" />
                  <FcDetailField label="Updated" :value="formatDate(fn.updatedAt)" />
                </div>
              </template>
            </FcFormSection>

            <FcFormSection title="Hosts" flat>
              <ProgressSpinner v-if="statusLoading" style="width: 24px; height: 24px" />
              <p v-else-if="!status || status.hosts.length === 0" class="hosts-empty">
                No hosts have reported for this function's pool yet.
              </p>
              <table v-else class="hosts-table">
                <thead>
                  <tr>
                    <th>Host</th>
                    <th>Pool</th>
                    <th>State</th>
                    <th>Last Heartbeat</th>
                    <th>Versions</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="host in status.hosts" :key="host.hostId">
                    <td><code>{{ host.hostId }}</code></td>
                    <td>{{ host.pool }}</td>
                    <td>
                      <Tag :value="host.state" :severity="hostStateSeverity(host.state)" />
                      <span v-if="host.stale" class="stale-flag">stale</span>
                    </td>
                    <td>{{ heartbeatAge(host.lastHeartbeat) }}</td>
                    <td>
                      <div class="loaded-versions">
                        <span
                          v-for="loaded in host.loaded"
                          :key="loaded.version"
                          v-tooltip="loaded.error"
                          class="loaded-version"
                        >
                          <Tag
                            :value="`v${loaded.version} ${loaded.state}`"
                            :severity="loadedStateSeverity(loaded.state)"
                          />
                        </span>
                        <span v-if="host.loaded.length === 0">—</span>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </FcFormSection>

            <FcFormSection v-if="!editing && canManage" title="Actions" flat>
              <div class="action-items">
                <div class="action-item">
                  <div class="action-info">
                    <strong>Delete Function</strong>
                    <p>Permanently deletes this function and everything published under it.</p>
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
            </FcFormSection>
          </TabPanel>

          <TabPanel value="versions">
            <FunctionVersionsTab :address="fn.address" @changed="onVersionsChanged" />
          </TabPanel>

          <TabPanel value="config">
            <FunctionConfigSecretsTab :address="fn.address" :live-version="fn.live?.version" />
          </TabPanel>

          <TabPanel value="routes">
            <FunctionPublicRoutesTab
              :address="fn.address"
              :client-id="fn.clientId"
              :has-live-version="!!fn.live"
            />
          </TabPanel>

          <TabPanel v-if="canInvoke" value="invoke">
            <FunctionInvokeTab
              :address="fn.address"
              :versions="invokeVersions"
              :live-version="fn.live?.version"
            />
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>

    <template v-if="editing" #footer>
      <FcFormActions :bordered="false">
        <Button v-if="dirty" label="Discard" severity="secondary" outlined @click="cancelEditing" />
        <Button label="Save" :disabled="!dirty" :loading="saving" @click="saveChanges" />
      </FcFormActions>
    </template>
  </EntityDrawer>
</template>

<style scoped>
.action-items {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.action-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
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

.hosts-empty {
  color: #64748b;
  font-size: 13px;
  margin: 0;
}

.hosts-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.hosts-table th {
  text-align: left;
  padding: 8px 12px;
  color: #64748b;
  font-weight: 600;
  border-bottom: 1px solid #e2e8f0;
}

.hosts-table td {
  padding: 8px 12px;
  border-bottom: 1px solid #f1f5f9;
  vertical-align: top;
}

.stale-flag {
  margin-left: 6px;
  font-size: 11px;
  color: #b91c1c;
}

.loaded-versions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
