<script setup lang="ts">
import { ref, onMounted } from "vue";
import { configApi } from "@/api/config";
import { usePlatformConfigStore } from "@/stores/platformConfig";
import { toast } from "@/utils/errorBus";
import { getErrorMessage } from "@/utils/errors";

const platformConfig = usePlatformConfigStore();

const DEFAULT_NAME = "Flowcatalyst";
const platformName = ref(DEFAULT_NAME);
const loading = ref(true);
const saving = ref(false);
const error = ref("");

onMounted(load);

async function load() {
	loading.value = true;
	try {
		const stored = await configApi.getPlatformName();
		platformName.value = stored?.trim() || DEFAULT_NAME;
	} catch (e) {
		// No value yet → default. Only surface real errors.
		error.value = getErrorMessage(e, "Could not load the platform name.");
	} finally {
		loading.value = false;
	}
}

async function save() {
	error.value = "";
	const name = platformName.value.trim();
	if (!name) {
		error.value = "Platform name cannot be empty.";
		return;
	}
	saving.value = true;
	try {
		await configApi.setPlatformName(name);
		// Refresh the global config so the title/brand update without a reload.
		await platformConfig.loadConfig(true);
		toast.success("Saved", "Platform name updated.");
	} catch (e) {
		error.value = getErrorMessage(e, "Could not save the platform name.");
	} finally {
		saving.value = false;
	}
}

function resetToDefault() {
	platformName.value = DEFAULT_NAME;
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Names</h1>
        <p class="page-subtitle">Set the name used across the platform</p>
      </div>
    </header>

    <div v-if="loading" class="loading-container">
      <ProgressSpinner strokeWidth="3" />
    </div>

    <div v-else class="fc-card settings-card">
      <h2 class="section-title">Platform Name</h2>

      <div class="field">
        <label for="platform-name">Platform name</label>
        <InputText
          id="platform-name"
          v-model="platformName"
          class="w-full"
          placeholder="Flowcatalyst"
          @keyup.enter="save"
        />
        <small class="hint">
          Shown wherever the product is named to users: security emails, the
          authenticator-app label for two-factor codes, passkey prompts, and the
          browser tab. Defaults to “Flowcatalyst”.
        </small>
      </div>

      <p v-if="error" class="error-text">{{ error }}</p>

      <div class="form-actions">
        <Button
          label="Reset to default"
          text
          severity="secondary"
          icon="pi pi-replay"
          @click="resetToDefault"
        />
        <Button label="Save" icon="pi pi-check" :loading="saving" @click="save" />
      </div>

      <p class="note">
        The authenticator-app and email names update immediately. The passkey
        prompt name updates on the next server restart.
      </p>
    </div>
  </div>
</template>

<style scoped>
.loading-container {
  display: flex;
  justify-content: center;
  padding: 60px;
}

.settings-card {
  max-width: 640px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #243b53;
  margin: 0 0 20px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e2e8f0;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field label {
  font-size: 14px;
  font-weight: 500;
  color: #334e68;
}

.hint {
  font-size: 12px;
  color: #64748b;
}

.error-text {
  margin: 16px 0 0;
  font-size: 13px;
  color: #b91c1c;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid #e2e8f0;
}

.note {
  margin: 16px 0 0;
  font-size: 12px;
  color: #94a3b8;
}

.w-full {
  width: 100%;
}
</style>
