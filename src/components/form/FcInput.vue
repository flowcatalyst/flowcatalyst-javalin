<script setup lang="ts">
import { computed } from "vue";
import { useField } from "vee-validate";

const props = withDefaults(
	defineProps<{
		name: string;
		label?: string;
		type?: "text" | "email" | "password" | "number" | "tel" | "url";
		placeholder?: string;
		hint?: string;
		disabled?: boolean;
		showPasswordStrength?: boolean;
	}>(),
	{
		type: "text",
		placeholder: "",
		hint: "",
		disabled: false,
		showPasswordStrength: false,
	},
);

const { value, errorMessage, handleBlur, meta } = useField<string>(
	() => props.name,
);

const invalid = computed(() => meta.touched && !!errorMessage.value);
const inputId = computed(() => `fc-input-${props.name}`);
</script>

<template>
  <div class="fc-input-wrapper">
    <label v-if="label" :for="inputId" class="fc-input-label">
      {{ label }}
    </label>

    <!-- Password input -->
    <Password
      v-if="type === 'password'"
      :id="inputId"
      v-model="value"
      :disabled="disabled"
      :placeholder="placeholder"
      :feedback="showPasswordStrength"
      :invalid="invalid"
      toggleMask
      inputClass="w-full"
      class="w-full"
      @blur="handleBlur"
    />

    <!-- Regular text input -->
    <InputText
      v-else
      :id="inputId"
      v-model="value"
      :type="type"
      :disabled="disabled"
      :placeholder="placeholder"
      :invalid="invalid"
      class="w-full"
      @blur="handleBlur"
    />

    <small v-if="hint && !errorMessage" class="fc-input-hint">{{ hint }}</small>
    <small v-if="errorMessage" class="fc-input-error">{{ errorMessage }}</small>
  </div>
</template>

<style scoped>
.fc-input-wrapper {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.fc-input-label {
  font-size: 14px;
  font-weight: 500;
  color: #334e68;
}

.fc-input-hint {
  color: #627d98;
  font-size: 12px;
}

.fc-input-error {
  color: #dc2626;
  font-size: 12px;
}
</style>
