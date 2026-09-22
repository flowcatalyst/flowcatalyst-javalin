<script setup lang="ts">
// Config & secrets tab (docs/spec/function-ui.md §2.1, function-context.md).
// Two tables of declared keys: config values are plain and editable inline;
// secret values are never returned by the API and must never appear back in
// this component's DOM after a set (U7).
//
// `declared`/`missing`/`declaredBy` are now fully server-computed
// (function-context.md §1, S1): `declared` is the union of the live
// manifest's keys and the newest non-retired version's (the CANDIDATE
// `PromoteVersion.requireSettingsPresent` actually checks), and
// `declaredBy` names, per key, which version(s) declared it — so this tab no
// longer fans out `listVersions` + `getVersion` per version itself just to
// compute the same union. A key declared only by a non-live version (i.e.
// not by `liveVersion`) is flagged "declared by v<n>".
import { computed, ref, watch } from "vue";
import { toast } from "@/utils/errorBus";
import { useConfirm } from "primevue/useconfirm";
import { ApiError } from "@/api/client";
import {
	functionsApi,
	type ConfigResponse,
	type SecretListResponse,
} from "@/api/functions";
import { useAuthStore } from "@/stores/auth";
import { userHasPermission } from "@/stores/permissions";

const props = defineProps<{
	address: string;
	/** The live version's number (`fn.live.version`), when there is one —
	 * used only to decide whether a key's declaring version(s) include the
	 * live one, so the "declared by v<n>" tag never fires for a key live
	 * already declares. */
	liveVersion?: number;
}>();

const authStore = useAuthStore();
const confirm = useConfirm();

const canManage = computed(() =>
	userHasPermission(authStore.user, "platform:function:secret:manage"),
);

const config = ref<ConfigResponse | null>(null);
const configLoading = ref(true);
const savingConfigKey = ref<string | null>(null);

const secrets = ref<SecretListResponse | null>(null);
const secretsLoading = ref(true);
/** Set when the secrets routes answered 503 (no FLOWCATALYST_APP_KEY) — a
 * disabled state with the reason, never an error toast (spec §2.1). */
const secretsDisabledReason = ref<string | null>(null);
const savingSecretKey = ref<string | null>(null);

watch(
	() => props.address,
	async (addr) => {
		if (!addr) return;
		await Promise.all([loadConfig(addr), loadSecrets(addr)]);
	},
	{ immediate: true },
);

async function loadConfig(addr: string) {
	configLoading.value = true;
	try {
		config.value = await functionsApi.getConfig(addr);
	} catch {
		config.value = null;
	} finally {
		configLoading.value = false;
	}
}

async function loadSecrets(addr: string) {
	secretsLoading.value = true;
	secretsDisabledReason.value = null;
	try {
		secrets.value = await functionsApi.listSecrets(addr, undefined, {
			suppressGlobalErrorToast: true,
		});
	} catch (e) {
		secrets.value = null;
		if (e instanceof ApiError && e.status === 503) {
			secretsDisabledReason.value = e.message;
		} else {
			secretsDisabledReason.value = null;
		}
	} finally {
		secretsLoading.value = false;
	}
}

/** key -> the version numbers whose manifest declares it, from the
 * response's `declaredBy` (live first when it declares the key, since the
 * server lists live's entry first — S1). */
function declaredByVersions(
	entries: { version: number; keys: string[] }[] | undefined,
): Map<string, number[]> {
	const map = new Map<string, number[]>();
	for (const entry of entries ?? []) {
		for (const key of entry.keys) {
			const existing = map.get(key);
			if (existing) existing.push(entry.version);
			else map.set(key, [entry.version]);
		}
	}
	return map;
}

const configDeclaredBy = computed(() => declaredByVersions(config.value?.declaredBy));
const secretsDeclaredBy = computed(() => declaredByVersions(secrets.value?.declaredBy));

/** The highest declaring version to flag with "declared by v<n>", or null
 * when the key isn't declared at all, or IS declared by the live version
 * (nothing to flag — it's already live). */
function nonLiveDeclaringVersion(declaredBy: number[], liveVersion: number | undefined): number | null {
	if (declaredBy.length === 0) return null;
	if (liveVersion !== undefined && declaredBy.includes(liveVersion)) return null;
	return Math.max(...declaredBy);
}

interface ConfigRow {
	key: string;
	value: string | undefined;
	declaredBy: number[];
	declaredByTag: number | null;
}

const configRows = computed<ConfigRow[]>(() => {
	const declaredBy = configDeclaredBy.value;
	const values = config.value?.values ?? {};
	const keys = new Set<string>([...declaredBy.keys(), ...Object.keys(values)]);
	return [...keys].sort().map((key) => {
		const versions = declaredBy.get(key) ?? [];
		return {
			key,
			value: values[key],
			declaredBy: versions,
			declaredByTag: nonLiveDeclaringVersion(versions, props.liveVersion),
		};
	});
});

interface SecretRow {
	key: string;
	isSet: boolean;
	declaredBy: number[];
	declaredByTag: number | null;
	updatedAt?: string;
	updatedBy?: string;
}

const secretRows = computed<SecretRow[]>(() => {
	const declaredBy = secretsDeclaredBy.value;
	const byKey = new Map((secrets.value?.keys ?? []).map((k) => [k.key, k]));
	const keys = new Set<string>([...declaredBy.keys(), ...byKey.keys()]);
	return [...keys].sort().map((key) => {
		const entry = byKey.get(key);
		const versions = declaredBy.get(key) ?? [];
		return {
			key,
			isSet: !!entry,
			declaredBy: versions,
			declaredByTag: nonLiveDeclaringVersion(versions, props.liveVersion),
			updatedAt: entry?.updatedAt,
			updatedBy: entry?.updatedBy,
		};
	});
});

// SETTINGS_MISSING (spec §2.1): a declared key with no value — now fully
// server-computed (S1: `missing` already accounts for the candidate
// version, not only live).
const missingCount = computed(() => {
	const configMissing = config.value?.missing?.length ?? 0;
	const secretsMissing = secrets.value?.missing?.length ?? 0;
	return configMissing + secretsMissing;
});

// Config inline edit
const editingConfigKey = ref<string | null>(null);
const editingConfigValue = ref("");

function startEditConfig(row: ConfigRow) {
	editingConfigKey.value = row.key;
	editingConfigValue.value = row.value ?? "";
}
function cancelEditConfig() {
	editingConfigKey.value = null;
	editingConfigValue.value = "";
}
async function putConfigValue(key: string, value: string) {
	const values = { ...config.value?.values, [key]: value };
	config.value = await functionsApi.setConfig(props.address, { values });
}
async function saveConfig(key: string) {
	savingConfigKey.value = key;
	try {
		await putConfigValue(key, editingConfigValue.value);
		toast.success("Success", `${key} updated`);
		editingConfigKey.value = null;
		editingConfigValue.value = "";
	} catch {
		// errors surface via the global error toast
	} finally {
		savingConfigKey.value = null;
	}
}

// "Add key" row (spec follow-up for gap 2): sets a config key that no
// loaded manifest declares yet — the only way to set a key before ANY
// version has been fetched (e.g. versions still loading/failed) or for a
// key the operator knows is coming.
const newConfigKey = ref("");
const newConfigValue = ref("");
const addingConfigKey = ref(false);

async function addConfigKey() {
	const key = newConfigKey.value.trim();
	if (!key) return;
	addingConfigKey.value = true;
	try {
		await putConfigValue(key, newConfigValue.value);
		toast.success("Success", `${key} set`);
		newConfigKey.value = "";
		newConfigValue.value = "";
	} catch {
		// errors surface via the global error toast
	} finally {
		addingConfigKey.value = false;
	}
}

// Secrets set/replace — a per-row inline form, torn down (and its model
// reset) the instant the save completes so the value never lingers in the
// DOM or in this component's reactive state.
const editingSecretKey = ref<string | null>(null);
const editingSecretValue = ref("");

function startSetSecret(row: SecretRow) {
	editingSecretKey.value = row.key;
	editingSecretValue.value = "";
}
function cancelSetSecret() {
	editingSecretKey.value = null;
	editingSecretValue.value = "";
}
async function putSecretValue(key: string, value: string) {
	await functionsApi.setSecret(
		props.address,
		key,
		{ value },
		{ suppressGlobalErrorToast: true },
	);
}
async function saveSecret(key: string) {
	savingSecretKey.value = key;
	try {
		await putSecretValue(key, editingSecretValue.value);
		toast.success("Success", `${key} set`);
	} catch (e) {
		toast.error(
			"Failed to set secret",
			e instanceof ApiError ? e.message : "Request failed",
		);
	} finally {
		// Clear the typed value from state and close the form regardless of
		// outcome — it must never be retrievable from this component again.
		editingSecretKey.value = null;
		editingSecretValue.value = "";
		savingSecretKey.value = null;
		await loadSecrets(props.address);
	}
}

// "Add key" row for secrets — same rationale as addConfigKey above.
const newSecretKey = ref("");
const newSecretValue = ref("");
const addingSecretKey = ref(false);

async function addSecretKey() {
	const key = newSecretKey.value.trim();
	const value = newSecretValue.value;
	if (!key || !value) return;
	addingSecretKey.value = true;
	try {
		await putSecretValue(key, value);
		toast.success("Success", `${key} set`);
	} catch (e) {
		toast.error(
			"Failed to set secret",
			e instanceof ApiError ? e.message : "Request failed",
		);
	} finally {
		newSecretKey.value = "";
		newSecretValue.value = "";
		addingSecretKey.value = false;
		await loadSecrets(props.address);
	}
}

function confirmDeleteSecret(row: SecretRow) {
	confirm.require({
		message: `Delete the secret "${row.key}"? Functions requiring it will fail until it is set again.`,
		header: "Delete Secret",
		icon: "pi pi-exclamation-triangle",
		acceptLabel: "Delete",
		acceptClass: "p-button-danger",
		accept: () => deleteSecret(row.key),
	});
}
async function deleteSecret(key: string) {
	try {
		await functionsApi.deleteSecret(props.address, key, {
			suppressGlobalErrorToast: true,
		});
		toast.success("Success", `${key} deleted`);
		await loadSecrets(props.address);
	} catch (e) {
		toast.error(
			"Failed to delete secret",
			e instanceof ApiError ? e.message : "Request failed",
		);
	}
}
</script>

<template>
  <div class="config-secrets-tab">
    <Message v-if="missingCount > 0" severity="warn" :closable="false" class="settings-banner">
      {{ missingCount }} declared key{{ missingCount === 1 ? " has" : "s have" }} no value —
      promote will refuse with <code>SETTINGS_MISSING</code> until every declared config and
      secret key has a value.
    </Message>

    <FcFormSection title="Config" flat>
      <ProgressSpinner v-if="configLoading" style="width: 24px; height: 24px" />
      <template v-else>
        <p v-if="configRows.length === 0" class="empty-hint">
          No config keys declared by the live manifest or the candidate version yet.
        </p>
        <table v-else class="kv-table">
          <thead>
            <tr>
              <th>Key</th>
              <th>Value</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in configRows" :key="row.key">
              <td>
                <code>{{ row.key }}</code>
                <Tag v-if="row.declaredBy.length === 0" value="not declared" severity="warn" class="flag-tag" />
                <Tag
                  v-else-if="row.declaredByTag !== null"
                  :value="`declared by v${row.declaredByTag}`"
                  severity="info"
                  class="flag-tag"
                  data-testid="declared-by-tag"
                />
              </td>
              <td>
                <template v-if="editingConfigKey === row.key">
                  <InputText v-model="editingConfigValue" size="small" />
                </template>
                <template v-else>
                  <span v-if="row.value !== undefined">{{ row.value }}</span>
                  <span v-else class="unset">not set</span>
                </template>
              </td>
              <td class="row-actions">
                <template v-if="editingConfigKey === row.key">
                  <Button
                    icon="pi pi-check"
                    text
                    size="small"
                    :loading="savingConfigKey === row.key"
                    @click="saveConfig(row.key)"
                  />
                  <Button icon="pi pi-times" text size="small" @click="cancelEditConfig" />
                </template>
                <Button
                  v-else-if="canManage"
                  icon="pi pi-pencil"
                  text
                  size="small"
                  @click="startEditConfig(row)"
                />
              </td>
            </tr>
          </tbody>
        </table>

        <div v-if="canManage" class="add-key-row" data-testid="add-config-key-row">
          <InputText
            v-model="newConfigKey"
            placeholder="Key"
            size="small"
            data-testid="add-config-key-input"
          />
          <InputText
            v-model="newConfigValue"
            placeholder="Value"
            size="small"
            data-testid="add-config-value-input"
          />
          <Button
            label="Add key"
            size="small"
            text
            :loading="addingConfigKey"
            :disabled="!newConfigKey.trim()"
            data-testid="add-config-key-button"
            @click="addConfigKey"
          />
        </div>
      </template>
    </FcFormSection>

    <FcFormSection title="Secrets" flat>
      <Message v-if="secretsDisabledReason" severity="secondary" :closable="false" data-testid="secrets-disabled">
        Secrets management is unavailable: {{ secretsDisabledReason }}
      </Message>
      <template v-else>
        <ProgressSpinner v-if="secretsLoading" style="width: 24px; height: 24px" />
        <template v-else>
          <p v-if="secretRows.length === 0" class="empty-hint">
            No secret keys declared by the live manifest or the candidate version yet.
          </p>
          <table v-else class="kv-table">
            <thead>
              <tr>
                <th>Key</th>
                <th>Status</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in secretRows" :key="row.key">
                <td>
                  <code>{{ row.key }}</code>
                  <Tag v-if="row.declaredBy.length === 0" value="not declared" severity="warn" class="flag-tag" />
                  <Tag
                    v-else-if="row.declaredByTag !== null"
                    :value="`declared by v${row.declaredByTag}`"
                    severity="info"
                    class="flag-tag"
                    data-testid="declared-by-tag"
                  />
                </td>
                <td>
                  <template v-if="editingSecretKey === row.key">
                    <InputText
                      v-model="editingSecretValue"
                      type="password"
                      size="small"
                      placeholder="New value"
                      data-testid="secret-value-input"
                    />
                  </template>
                  <template v-else>
                    <Tag
                      :value="row.isSet ? 'set' : 'not set'"
                      :severity="row.isSet ? 'success' : 'secondary'"
                    />
                  </template>
                </td>
                <td class="row-actions">
                  <template v-if="editingSecretKey === row.key">
                    <Button
                      label="Save"
                      size="small"
                      :loading="savingSecretKey === row.key"
                      :disabled="!editingSecretValue"
                      data-testid="secret-save-button"
                      @click="saveSecret(row.key)"
                    />
                    <Button label="Cancel" text size="small" @click="cancelSetSecret" />
                  </template>
                  <template v-else-if="canManage">
                    <Button
                      :label="row.isSet ? 'Replace' : 'Set'"
                      text
                      size="small"
                      @click="startSetSecret(row)"
                    />
                    <Button
                      v-if="row.isSet"
                      icon="pi pi-trash"
                      text
                      size="small"
                      severity="danger"
                      @click="confirmDeleteSecret(row)"
                    />
                  </template>
                </td>
              </tr>
            </tbody>
          </table>

          <div v-if="canManage" class="add-key-row" data-testid="add-secret-key-row">
            <InputText
              v-model="newSecretKey"
              placeholder="Key"
              size="small"
              data-testid="add-secret-key-input"
            />
            <InputText
              v-model="newSecretValue"
              type="password"
              placeholder="Value"
              size="small"
              data-testid="add-secret-value-input"
            />
            <Button
              label="Add key"
              size="small"
              text
              :loading="addingSecretKey"
              :disabled="!newSecretKey.trim() || !newSecretValue"
              data-testid="add-secret-key-button"
              @click="addSecretKey"
            />
          </div>
        </template>
      </template>
    </FcFormSection>
  </div>
</template>

<style scoped>
.settings-banner {
  margin-bottom: 16px;
}

.empty-hint {
  color: #64748b;
  font-size: 13px;
}

.kv-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.kv-table th {
  text-align: left;
  padding: 8px 12px;
  color: #64748b;
  font-weight: 600;
  border-bottom: 1px solid #e2e8f0;
}

.kv-table td {
  padding: 8px 12px;
  border-bottom: 1px solid #f1f5f9;
  vertical-align: middle;
}

.flag-tag {
  margin-left: 6px;
}

.unset {
  color: #94a3b8;
  font-style: italic;
}

.row-actions {
  display: flex;
  gap: 4px;
  white-space: nowrap;
}

.add-key-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed #e2e8f0;
}
</style>
