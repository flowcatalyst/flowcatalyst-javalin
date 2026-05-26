<script setup lang="ts">
import { ref, computed, watch } from "vue";

export type SecretProviderType =
	| "encrypt"
	| "aws-sm"
	| "aws-ps"
	| "gcp-sm"
	| "vault";

export interface SecretProviderOption {
	label: string;
	value: SecretProviderType;
	prefix: string;
	placeholder: string;
	description: string;
}

const secretProviderOptions: SecretProviderOption[] = [
	{
		label: "Local Encrypted",
		value: "encrypt",
		prefix: "encrypt:",
		placeholder: "your-client-secret",
		description: "Encrypt and store locally (requires APP_KEY)",
	},
	{
		label: "AWS Secrets Manager",
		value: "aws-sm",
		prefix: "aws-sm://",
		placeholder: "secret-name",
		description: "Reference a secret in AWS Secrets Manager",
	},
	{
		label: "AWS Parameter Store",
		value: "aws-ps",
		prefix: "aws-ps://",
		placeholder: "/path/to/parameter",
		description: "Reference a parameter in AWS SSM Parameter Store",
	},
	{
		label: "GCP Secret Manager",
		value: "gcp-sm",
		prefix: "gcp-sm://",
		placeholder: "secret-name",
		description: "Reference a secret in Google Cloud Secret Manager",
	},
	{
		label: "HashiCorp Vault",
		value: "vault",
		prefix: "vault://",
		placeholder: "path/to/secret#key",
		description: "Reference a secret in HashiCorp Vault",
	},
];

const props = defineProps<{
	modelValue: string;
	label?: string;
	helpText?: string;
	disabled?: boolean;
}>();

const emit = defineEmits<{
	(e: "update:modelValue", value: string): void;
	(e: "validate", secretRef: string): void;
}>();

// Internal state
const selectedProvider = ref<SecretProviderType>("encrypt");
const secretValue = ref("");

// Parse initial value if provided
function parseSecretRef(fullRef: string) {
	if (!fullRef) {
		selectedProvider.value = "encrypt";
		secretValue.value = "";
		return;
	}

	for (const option of secretProviderOptions) {
		if (fullRef.startsWith(option.prefix)) {
			selectedProvider.value = option.value;
			secretValue.value = fullRef.substring(option.prefix.length);
			return;
		}
	}

	// Unknown format, default to encrypt with full value
	selectedProvider.value = "encrypt";
	secretValue.value = fullRef;
}

// Initialize from modelValue
watch(
	() => props.modelValue,
	(newVal) => {
		parseSecretRef(newVal);
	},
	{ immediate: true },
);

// Build full reference when either part changes
const fullSecretRef = computed(() => {
	if (!secretValue.value.trim()) return "";
	const option = secretProviderOptions.find(
		(o) => o.value === selectedProvider.value,
	);
	return option
		? option.prefix + secretValue.value.trim()
		: secretValue.value.trim();
});

// Emit changes
watch(fullSecretRef, (newVal) => {
	emit("update:modelValue", newVal);
});

const currentOption = computed(() =>
	secretProviderOptions.find((o) => o.value === selectedProvider.value),
);

const currentPlaceholder = computed(
	() => currentOption.value?.placeholder || "",
);
const currentDescription = computed(
	() => currentOption.value?.description || "",
);
const currentPrefix = computed(() => currentOption.value?.prefix || "");

function handleValidate() {
	if (fullSecretRef.value) {
		emit("validate", fullSecretRef.value);
	}
}
</script>

<template>
  <div class="secret-ref-input">
    <label v-if="label" class="field-label">{{ label }}</label>

    <div class="input-row">
      <Select
        v-model="selectedProvider"
        :options="secretProviderOptions"
        optionLabel="label"
        optionValue="value"
        class="provider-dropdown"
        :disabled="disabled"
      >
        <template #option="{ option }">
          <div class="provider-option">
            <span class="provider-label">{{ option.label }}</span>
            <small class="provider-desc">{{ option.description }}</small>
          </div>
        </template>
      </Select>

      <InputGroup class="secret-input-group">
        <InputGroupAddon class="prefix-addon">
          <code>{{ currentPrefix }}</code>
        </InputGroupAddon>
        <InputText
          v-model="secretValue"
          :placeholder="currentPlaceholder"
          :disabled="disabled"
          class="secret-input"
        />
      </InputGroup>

      <Button
        v-if="fullSecretRef"
        icon="pi pi-check-circle"
        severity="secondary"
        outlined
        v-tooltip="'Validate secret reference'"
        :disabled="disabled || !fullSecretRef"
        @click="handleValidate"
        class="validate-btn"
      />
    </div>

    <small v-if="helpText" class="field-help">{{ helpText }}</small>
    <small v-else class="field-help">{{ currentDescription }}</small>
  </div>
</template>

<style scoped>
.secret-ref-input {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field-label {
  font-weight: 500;
  color: #334155;
}

.input-row {
  display: flex;
  gap: 8px;
  align-items: stretch;
}

.provider-dropdown {
  width: 200px;
  flex-shrink: 0;
}

.secret-input-group {
  flex: 1;
}

.prefix-addon {
  background: #f1f5f9;
  border-color: #e2e8f0;
  padding: 0 8px;
}

.prefix-addon code {
  font-size: 12px;
  color: #64748b;
  white-space: nowrap;
}

.secret-input {
  flex: 1;
}

.validate-btn {
  flex-shrink: 0;
}

.field-help {
  color: #64748b;
  font-size: 12px;
}

.provider-option {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 0;
}

.provider-label {
  font-weight: 500;
}

.provider-desc {
  color: #64748b;
  font-size: 11px;
}
</style>
