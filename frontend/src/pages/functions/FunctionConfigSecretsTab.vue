<script setup lang="ts">
// Config & secrets tab (docs/spec/function-ui.md §2.1, function-context.md).
// Two tables over the LIVE manifest's declared keys: config values are
// plain and editable inline; secret values are never returned by the API
// and must never appear back in this component's DOM after a set (U7).
import { computed, ref, watch } from "vue";
import { toast } from "@/utils/errorBus";
import { useConfirm } from "primevue/useconfirm";
import { ApiError } from "@/api/client";
import { functionsApi, type ConfigResponse, type SecretListResponse } from "@/api/functions";
import { useAuthStore } from "@/stores/auth";
import { userHasPermission } from "@/stores/permissions";

const props = defineProps<{
	address: string;
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
		secrets.value = await functionsApi.listSecrets(addr, {
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

interface ConfigRow {
	key: string;
	value: string | undefined;
	declared: boolean;
}

const configRows = computed<ConfigRow[]>(() => {
	if (!config.value) return [];
	const keys = new Set<string>([
		...config.value.declared,
		...Object.keys(config.value.values),
	]);
	return [...keys].sort().map((key) => ({
		key,
		value: config.value?.values[key],
		declared: config.value?.declared.includes(key) ?? false,
	}));
});

interface SecretRow {
	key: string;
	isSet: boolean;
	declared: boolean;
	updatedAt?: string;
	updatedBy?: string;
}

const secretRows = computed<SecretRow[]>(() => {
	if (!secrets.value) return [];
	const byKey = new Map(secrets.value.keys.map((k) => [k.key, k]));
	const keys = new Set<string>([...secrets.value.declared, ...byKey.keys()]);
	return [...keys].sort().map((key) => {
		const entry = byKey.get(key);
		return {
			key,
			isSet: !!entry,
			declared: secrets.value?.declared.includes(key) ?? false,
			updatedAt: entry?.updatedAt,
			updatedBy: entry?.updatedBy,
		};
	});
});

const missingCount = computed(
	() => (config.value?.missing.length ?? 0) + (secrets.value?.missing.length ?? 0),
);

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
async function saveConfig(key: string) {
	if (!config.value) return;
	savingConfigKey.value = key;
	try {
		const values = { ...config.value.values, [key]: editingConfigValue.value };
		config.value = await functionsApi.setConfig(props.address, { values });
		toast.success("Success", `${key} updated`);
		editingConfigKey.value = null;
		editingConfigValue.value = "";
	} catch {
		// errors surface via the global error toast
	} finally {
		savingConfigKey.value = null;
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
async function saveSecret(key: string) {
	savingSecretKey.value = key;
	try {
		await functionsApi.setSecret(
			props.address,
			key,
			{ value: editingSecretValue.value },
			{ suppressGlobalErrorToast: true },
		);
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
      <p v-else-if="configRows.length === 0" class="empty-hint">
        The live manifest declares no config keys.
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
              <Tag v-if="!row.declared" value="not declared" severity="warn" class="flag-tag" />
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
    </FcFormSection>

    <FcFormSection title="Secrets" flat>
      <Message v-if="secretsDisabledReason" severity="secondary" :closable="false" data-testid="secrets-disabled">
        Secrets management is unavailable: {{ secretsDisabledReason }}
      </Message>
      <template v-else>
        <ProgressSpinner v-if="secretsLoading" style="width: 24px; height: 24px" />
        <p v-else-if="secretRows.length === 0" class="empty-hint">
          The live manifest declares no secret keys.
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
                <Tag v-if="!row.declared" value="not declared" severity="warn" class="flag-tag" />
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
</style>
