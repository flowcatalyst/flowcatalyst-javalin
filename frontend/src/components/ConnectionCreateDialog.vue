<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, computed, watch } from "vue";
import { connectionsApi, type Connection } from "@/api/connections";
import {
	serviceAccountsApi,
	type ServiceAccount,
} from "@/api/service-accounts";

const props = defineProps<{
	visible: boolean;
	serviceAccountId?: string;
	clientId?: string;
}>();

const emit = defineEmits<{
	"update:visible": [value: boolean];
	created: [connection: Connection];
}>();


// Form fields
const code = ref("");
const name = ref("");
const description = ref("");
const externalId = ref("");
const selectedServiceAccountId = ref<string | null>(null);
const selectedClientId = ref<string | null>(null);

// Lookup data
const serviceAccounts = ref<ServiceAccount[]>([]);
const loadingServiceAccounts = ref(false);
const submitting = ref(false);
const errorMessage = ref<string | null>(null);

const CODE_PATTERN = /^[a-z][a-z0-9-]*$/;

const isCodeValid = computed(() => {
	return !code.value || CODE_PATTERN.test(code.value);
});

const isFormValid = computed(() => {
	const saId = props.serviceAccountId || selectedServiceAccountId.value;
	return (
		code.value.length >= 2 &&
		code.value.length <= 100 &&
		CODE_PATTERN.test(code.value) &&
		name.value.trim().length > 0 &&
		name.value.length <= 255 &&
		saId !== null
	);
});

// Reset form and load data when dialog opens
watch(
	() => props.visible,
	async (open) => {
		if (open) {
			code.value = "";
			name.value = "";
			description.value = "";
			externalId.value = "";
			selectedServiceAccountId.value = props.serviceAccountId || null;
			selectedClientId.value = props.clientId || null;
			errorMessage.value = null;

			if (!props.serviceAccountId) {
				await loadServiceAccounts();
			}
		}
	},
);

async function loadServiceAccounts() {
	loadingServiceAccounts.value = true;
	try {
		const response = await serviceAccountsApi.list();
		serviceAccounts.value = response.serviceAccounts;
	} catch (e) {
		console.error("Failed to load service accounts:", e);
	} finally {
		loadingServiceAccounts.value = false;
	}
}

async function onSubmit() {
	if (!isFormValid.value) return;

	submitting.value = true;
	errorMessage.value = null;

	const saId = props.serviceAccountId || selectedServiceAccountId.value!;
	const cId = props.clientId ?? selectedClientId.value;

	try {
		const connection = await connectionsApi.create({
			code: code.value,
			name: name.value,
			description: description.value || undefined,
			externalId: externalId.value || undefined,
			serviceAccountId: saId,
			clientId: cId || undefined,
		});
		toast.success("Success", "Connection created");
		emit("created", connection);
		emit("update:visible", false);
	} catch (e) {
		errorMessage.value =
			e instanceof Error ? e.message : "Failed to create connection";
	} finally {
		submitting.value = false;
	}
}

function close() {
	emit("update:visible", false);
}
</script>

<template>
  <Dialog
    :visible="visible"
    @update:visible="$emit('update:visible', $event)"
    header="Create Connection"
    :style="{ width: '600px' }"
    :modal="true"
    :closable="!submitting"
  >
    <form @submit.prevent="onSubmit" class="dialog-form">
      <div class="form-field">
        <label>Code <span class="required">*</span></label>
        <InputText
          v-model="code"
          placeholder="connection-code"
          class="full-width"
          :invalid="!!(code && !isCodeValid)"
        />
        <small v-if="code && !isCodeValid" class="p-error">
          Lowercase letters, numbers, hyphens only. Must start with a letter.
        </small>
        <small v-else class="hint">
          Unique identifier (2-100 characters)
        </small>
      </div>

      <div class="form-field">
        <label>Name <span class="required">*</span></label>
        <InputText
          v-model="name"
          placeholder="Connection display name"
          class="full-width"
          :invalid="name.length > 255"
        />
      </div>

      <div class="form-field">
        <label>Description</label>
        <Textarea
          v-model="description"
          placeholder="Optional description..."
          class="full-width"
          rows="2"
        />
      </div>

      <div class="form-field">
        <label>External ID</label>
        <InputText
          v-model="externalId"
          placeholder="Optional external identifier"
          class="full-width"
        />
      </div>

      <div v-if="!serviceAccountId" class="form-field">
        <label>Service Account <span class="required">*</span></label>
        <Select
          v-model="selectedServiceAccountId"
          :options="serviceAccounts"
          optionLabel="name"
          optionValue="id"
          placeholder="Select a service account"
          class="full-width"
          :loading="loadingServiceAccounts"
          :disabled="loadingServiceAccounts"
          filter
        >
          <template #option="{ option }">
            <div class="dropdown-option">
              <span class="option-name">{{ option.name }}</span>
              <span class="option-code">{{ option.code }}</span>
            </div>
          </template>
        </Select>
      </div>

      <div v-if="!clientId" class="form-field">
        <label>Client</label>
        <ClientSelect
          v-model="selectedClientId"
          placeholder="Anchor-level (leave empty) or select a client"
        />
        <small class="hint">
          Leave empty for an anchor-level connection.
        </small>
      </div>

      <Message v-if="errorMessage" severity="error" class="error-message">
        {{ errorMessage }}
      </Message>
    </form>

    <template #footer>
      <Button label="Cancel" text @click="close" :disabled="submitting" />
      <Button
        label="Create Connection"
        icon="pi pi-check"
        :loading="submitting"
        :disabled="!isFormValid"
        @click="onSubmit"
      />
    </template>
  </Dialog>
</template>

<style scoped>
.dialog-form {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.form-field {
  margin-bottom: 16px;
}

.form-field > label {
  display: block;
  font-weight: 500;
  margin-bottom: 6px;
}

.form-field .required {
  color: #ef4444;
}

.full-width {
  width: 100%;
}

.hint {
  display: block;
  font-size: 12px;
  color: #64748b;
  margin-top: 4px;
}

.dropdown-option {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.option-name {
  font-weight: 500;
}

.option-code {
  font-size: 12px;
  color: #64748b;
  font-family: monospace;
}

.error-message {
  margin-top: 8px;
}
</style>
