<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, computed } from "vue";
import { useRouter } from "vue-router";
import { clientsApi } from "@/api/clients";

const router = useRouter();

const name = ref("");
const identifier = ref("");
const submitting = ref(false);
const errorMessage = ref<string | null>(null);

const IDENTIFIER_PATTERN = /^[a-z][a-z0-9-]*$/;

const isIdentifierValid = computed(() => {
	return !identifier.value || IDENTIFIER_PATTERN.test(identifier.value);
});

const isFormValid = computed(() => {
	return (
		name.value.trim().length > 0 &&
		name.value.length <= 255 &&
		identifier.value.length >= 2 &&
		identifier.value.length <= 100 &&
		IDENTIFIER_PATTERN.test(identifier.value)
	);
});

async function onSubmit() {
	if (!isFormValid.value) return;

	submitting.value = true;
	errorMessage.value = null;

	try {
		const client = await clientsApi.create({
			name: name.value,
			identifier: identifier.value,
		});
		toast.success("Success", "Client created");
		router.push(`/clients/${client.id}`);
	} catch (e) {
		errorMessage.value =
			e instanceof Error ? e.message : "Failed to create client";
	} finally {
		submitting.value = false;
	}
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Create Client</h1>
        <p class="page-subtitle">Add a new customer client to the platform</p>
      </div>
    </header>

    <form @submit.prevent="onSubmit">
      <div class="form-card">
        <div class="form-field">
          <label>Name <span class="required">*</span></label>
          <InputText
            v-model="name"
            placeholder="Client display name"
            class="full-width"
            :invalid="name.length > 255"
          />
          <small class="char-count">{{ name.length }} / 255</small>
        </div>

        <div class="form-field">
          <label>Identifier <span class="required">*</span></label>
          <InputText
            v-model="identifier"
            placeholder="client-slug"
            class="full-width"
            :invalid="!!(identifier && !isIdentifierValid)"
          />
          <small v-if="identifier && !isIdentifierValid" class="p-error">
            Lowercase letters, numbers, hyphens only. Must start with a letter.
          </small>
          <small v-else class="hint">
            Unique identifier used in URLs and configurations (2-100 characters)
          </small>
        </div>

        <Message v-if="errorMessage" severity="error" class="error-message">
          {{ errorMessage }}
        </Message>

        <div class="form-actions">
          <Button
            label="Cancel"
            icon="pi pi-times"
            severity="secondary"
            outlined
            @click="router.push('/clients')"
          />
          <Button
            label="Create Client"
            icon="pi pi-check"
            type="submit"
            :loading="submitting"
            :disabled="!isFormValid"
          />
        </div>
      </div>
    </form>
  </div>
</template>

<style scoped>
.page-container {
  max-width: 600px;
}

.form-card {
  background: white;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  padding: 24px;
}

.form-field {
  margin-bottom: 20px;
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

.char-count {
  display: block;
  text-align: right;
  font-size: 12px;
  color: #94a3b8;
  margin-top: 4px;
}

.hint {
  display: block;
  font-size: 12px;
  color: #64748b;
  margin-top: 4px;
}

.error-message {
  margin-bottom: 16px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid #e2e8f0;
}
</style>
