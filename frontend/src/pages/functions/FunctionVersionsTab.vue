<script setup lang="ts">
// The Versions tab (docs/spec/function-ui.md §2.1). Its own component so it
// can be exercised in isolation (U5) without mounting the whole detail
// drawer or its Overview/status calls.
import { computed, ref, watch } from "vue";
import { toast } from "@/utils/errorBus";
import { useConfirm } from "primevue/useconfirm";
import {
	functionsApi,
	type AliasResponse,
	type Manifest,
	type PublishResponse,
	type VersionResponse,
} from "@/api/functions";
import { useAuthStore } from "@/stores/auth";
import { userHasPermission } from "@/stores/permissions";
import PublishVersionDrawer from "./PublishVersionDrawer.vue";

const props = defineProps<{
	address: string;
}>();

const emit = defineEmits<{
	/** A promote/retire changed the live alias or a version's state — the
	 * parent should reload the function + status (Overview/Hosts). */
	changed: [];
}>();

const authStore = useAuthStore();
const confirm = useConfirm();

const canPublish = computed(() =>
	userHasPermission(authStore.user, "platform:function:version:publish"),
);
const canPromote = computed(() =>
	userHasPermission(authStore.user, "platform:function:alias:promote"),
);

const versions = ref<VersionResponse[]>([]);
const loading = ref(true);
const expandedRows = ref<Record<number, boolean>>({});
const manifestByVersion = ref<Record<number, Manifest | null>>({});
const showPublishDrawer = ref(false);
const highlightVersion = ref<number | null>(null);

const aliases = ref<AliasResponse[]>([]);
const aliasesLoading = ref(true);

watch(
	() => props.address,
	async (addr) => {
		if (!addr) return;
		await Promise.all([loadVersions(addr), loadAliases(addr)]);
	},
	{ immediate: true },
);

async function loadVersions(addr: string) {
	loading.value = true;
	try {
		const result = await functionsApi.listVersions(addr);
		// Newest first — the version most likely to need attention (a fresh
		// PUBLISHED candidate, or the current live one) is what an operator
		// opens this tab to see.
		versions.value = [...result].sort((a, b) => b.version - a.version);
	} catch {
		versions.value = [];
	} finally {
		loading.value = false;
	}
}

async function loadAliases(addr: string) {
	aliasesLoading.value = true;
	try {
		aliases.value = await functionsApi.listAliases(addr);
	} catch {
		aliases.value = [];
	} finally {
		aliasesLoading.value = false;
	}
}

async function onRowExpand(event: { data: VersionResponse }) {
	const v = event.data;
	if (manifestByVersion.value[v.version] !== undefined) return;
	try {
		const full = await functionsApi.getVersion(props.address, v.version);
		manifestByVersion.value[v.version] = full.manifest ?? null;
	} catch {
		manifestByVersion.value[v.version] = null;
	}
}

function prettyManifest(version: number): string {
	const manifest = manifestByVersion.value[version];
	if (!manifest) return "—";
	return JSON.stringify(manifest, null, 2);
}

// Promote: enabled only for a READY version (U5). Retire: never for the
// live version (also U5) and never for one already RETIRED.
function canPromoteRow(v: VersionResponse): boolean {
	// READY and not already the live one — promoting the live version is the
	// platform's ALIAS_UNCHANGED conflict, which is not an error worth offering.
	return v.state === "READY" && !v.live;
}
function canRetireRow(v: VersionResponse): boolean {
	return !v.live && v.state !== "RETIRED";
}

// Promote opens a small dialog (not a bare confirm) because a named alias
// needs a name — `alias` defaults to `live`, matching `fn promote --alias`'s
// own default (spec `function-zones-and-aliases.md` §6). `live` still
// applies its manifest immediately (wiring materialised); any other alias
// is HTTP-only.
const showPromoteDialog = ref(false);
const promoteTarget = ref<VersionResponse | null>(null);
const promoteAliasName = ref("live");
const promoting = ref(false);

const promoteAliasValid = computed(() =>
	/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(promoteAliasName.value.trim()),
);

function openPromoteDialog(v: VersionResponse) {
	promoteTarget.value = v;
	promoteAliasName.value = "live";
	showPromoteDialog.value = true;
}

async function submitPromote() {
	const v = promoteTarget.value;
	if (!v || !promoteAliasValid.value) return;
	const alias = promoteAliasName.value.trim();
	promoting.value = true;
	try {
		await functionsApi.promote(props.address, v.version, alias);
		toast.success("Success", `Version ${v.version} promoted to ${alias}`);
		showPromoteDialog.value = false;
		await Promise.all([loadVersions(props.address), loadAliases(props.address)]);
		emit("changed");
	} catch {
		// errors surface via the global error toast
	} finally {
		promoting.value = false;
	}
}

function canDeleteAlias(a: AliasResponse): boolean {
	return a.alias !== "live";
}

function confirmDeleteAlias(a: AliasResponse) {
	confirm.require({
		message: `Remove alias "${a.alias}"? Anything still calling it stops working immediately.`,
		header: "Remove Alias",
		icon: "pi pi-exclamation-triangle",
		acceptLabel: "Remove",
		acceptClass: "p-button-danger",
		accept: () => deleteAlias(a),
	});
}

async function deleteAlias(a: AliasResponse) {
	try {
		await functionsApi.deleteAlias(props.address, a.alias);
		toast.success("Success", `Alias ${a.alias} removed`);
		await loadAliases(props.address);
		emit("changed");
	} catch {
		// errors surface via the global error toast
	}
}

function confirmRetire(v: VersionResponse) {
	confirm.require({
		message: `Retire version ${v.version}? It stops being loaded on any host.`,
		header: "Retire Version",
		icon: "pi pi-exclamation-triangle",
		acceptLabel: "Retire",
		acceptClass: "p-button-warning",
		accept: () => retire(v),
	});
}

async function retire(v: VersionResponse) {
	try {
		await functionsApi.retireVersion(props.address, v.version);
		toast.success("Success", `Version ${v.version} retired`);
		await loadVersions(props.address);
		emit("changed");
	} catch {
		// errors surface via the global error toast
	}
}

function onPublished(published: PublishResponse) {
	showPublishDrawer.value = false;
	highlightVersion.value = published.version;
	void loadVersions(props.address);
	emit("changed");
}

function shortDigest(digest: string): string {
	const [algo, hex] = digest.split(":");
	if (!hex || hex.length <= 14) return digest;
	return `${algo}:${hex.slice(0, 8)}…${hex.slice(-6)}`;
}

function copyDigest(digest: string) {
	void navigator.clipboard.writeText(digest);
	toast.info("Copied", "Digest copied to clipboard");
}

function stateSeverity(state: string): "success" | "info" | "secondary" {
	if (state === "READY") return "success";
	if (state === "PUBLISHED") return "info";
	return "secondary";
}

function formatDate(dateString?: string | null): string {
	if (!dateString) return "—";
	return new Date(dateString).toLocaleString();
}

function rowClass(data: VersionResponse) {
	return data.version === highlightVersion.value ? "highlighted-row" : undefined;
}
</script>

<template>
  <div class="versions-tab">
    <div class="versions-toolbar">
      <Button
        v-if="canPublish"
        label="Publish Version"
        icon="pi pi-upload"
        @click="showPublishDrawer = true"
      />
    </div>

    <ProgressSpinner v-if="loading" style="width: 24px; height: 24px" />
    <p v-else-if="versions.length === 0" class="versions-empty">No versions published yet.</p>
    <DataTable
      v-else
      :value="versions"
      data-key="version"
      v-model:expandedRows="expandedRows"
      :row-class="rowClass"
      @row-expand="onRowExpand"
    >
      <Column expander style="width: 3rem" />
      <Column header="Version">
        <template #body="{ data }">
          <span>v{{ data.version }}</span>
          <Tag v-if="data.live" value="LIVE" severity="success" class="live-tag" />
        </template>
      </Column>
      <Column header="State">
        <template #body="{ data }">
          <Tag :value="data.state" :severity="stateSeverity(data.state)" />
        </template>
      </Column>
      <Column header="Digest">
        <template #body="{ data }">
          <code>{{ shortDigest(data.digest) }}</code>
          <Button
            icon="pi pi-copy"
            text
            size="small"
            v-tooltip="'Copy full digest'"
            @click="copyDigest(data.digest)"
          />
        </template>
      </Column>
      <Column header="Signer">
        <template #body="{ data }">
          <span v-if="data.signer">{{ data.signer.issuer }} / {{ data.signer.subject }}</span>
          <span v-else>—</span>
        </template>
      </Column>
      <Column header="Published">
        <template #body="{ data }">{{ formatDate(data.publishedAt) }}</template>
      </Column>
      <Column header="Actions">
        <template #body="{ data }">
          <div class="row-actions">
            <Button
              v-if="canPromote"
              label="Promote"
              size="small"
              text
              :disabled="!canPromoteRow(data)"
              @click="openPromoteDialog(data)"
            />
            <Button
              v-if="canPublish"
              label="Retire"
              size="small"
              text
              severity="danger"
              :disabled="!canRetireRow(data)"
              @click="confirmRetire(data)"
            />
          </div>
        </template>
      </Column>
      <template #expansion="{ data }">
        <div class="manifest-expansion">
          <div class="manifest-meta">
            <span><strong>Artifact ref:</strong> <code>{{ data.artifactRef }}</code></span>
            <span><strong>Pool:</strong> {{ data.pool }}</span>
            <span><strong>Warm:</strong> {{ data.warm ? "yes" : "no" }}</span>
          </div>
          <pre class="manifest-json">{{ prettyManifest(data.version) }}</pre>
        </div>
      </template>
    </DataTable>

    <h3 class="aliases-heading">Aliases</h3>
    <ProgressSpinner v-if="aliasesLoading" style="width: 24px; height: 24px" />
    <p v-else-if="aliases.length === 0" class="versions-empty">No aliases yet.</p>
    <DataTable v-else :value="aliases" data-key="alias">
      <Column header="Alias">
        <template #body="{ data }">
          <span>{{ data.alias }}</span>
          <Tag v-if="data.alias === 'live'" value="LIVE" severity="success" class="live-tag" />
        </template>
      </Column>
      <Column header="Version">
        <template #body="{ data }">v{{ data.version }}</template>
      </Column>
      <Column header="Updated">
        <template #body="{ data }">{{ formatDate(data.updatedAt) }} by {{ data.updatedBy }}</template>
      </Column>
      <Column header="Actions">
        <template #body="{ data }">
          <Button
            v-if="canPromote"
            label="Delete"
            size="small"
            text
            severity="danger"
            :disabled="!canDeleteAlias(data)"
            v-tooltip="canDeleteAlias(data) ? undefined : 'live cannot be removed'"
            @click="confirmDeleteAlias(data)"
          />
        </template>
      </Column>
    </DataTable>

    <PublishVersionDrawer
      v-if="showPublishDrawer"
      :address="address"
      @close="showPublishDrawer = false"
      @published="onPublished"
    />

    <Dialog
      v-model:visible="showPromoteDialog"
      header="Point Alias"
      modal
      :style="{ width: '26rem' }"
    >
      <p v-if="promoteTarget" class="promote-intro">
        Point an alias at version {{ promoteTarget.version }}.
        <template v-if="promoteAliasName.trim() === 'live'">
          This applies its manifest immediately — pools, subscriptions, schedules and public
          routes are created, updated or removed to match it.
        </template>
        <template v-else>
          Named aliases are HTTP-only — no wiring changes, no manifest applied.
        </template>
      </p>
      <div class="field">
        <label for="promoteAlias">Alias name</label>
        <InputText id="promoteAlias" v-model="promoteAliasName" class="w-full" autofocus />
        <small v-if="!promoteAliasValid" class="field-error">
          1-63 characters of a-z, 0-9 and '-', not starting or ending with '-'.
        </small>
      </div>
      <template #footer>
        <Button label="Cancel" text @click="showPromoteDialog = false" />
        <Button
          label="Promote"
          :disabled="!promoteAliasValid || promoting"
          :loading="promoting"
          @click="submitPromote"
        />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.versions-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.versions-empty {
  color: #64748b;
  font-size: 13px;
}

.aliases-heading {
  margin: 24px 0 12px;
  font-size: 15px;
}

.promote-intro {
  font-size: 13px;
  color: #475569;
  margin-top: 0;
}

.field {
  margin-bottom: 16px;
}

.field label {
  display: block;
  margin-bottom: 4px;
  font-weight: 600;
  font-size: 13px;
}

.field-error {
  color: #dc2626;
  display: block;
  margin-top: 4px;
}

.live-tag {
  margin-left: 6px;
}

.row-actions {
  display: flex;
  gap: 4px;
}

.manifest-expansion {
  padding: 12px 16px;
  background: #f8fafc;
}

.manifest-meta {
  display: flex;
  gap: 20px;
  font-size: 13px;
  margin-bottom: 10px;
  color: #475569;
}

.manifest-json {
  margin: 0;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 360px;
  overflow: auto;
  background: #0f172a;
  color: #e2e8f0;
  padding: 12px;
  border-radius: 6px;
}

:deep(.highlighted-row) {
  background: #ecfdf5 !important;
}
</style>
