<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, computed, onMounted } from "vue";
import { useRoute } from "vue-router";
import {
	eventTypesApi,
	type EventType,
	type SchemaType,
} from "@/api/event-types";
import { useReturnTo } from "@/composables/useReturnTo";

const route = useRoute();
const { returnTo, forwardFrom } = useReturnTo();

const loading = ref(true);
const eventType = ref<EventType | null>(null);
const submitting = ref(false);
const errorMessage = ref<string | null>(null);

// Form state
const version = ref("");
const mimeType = ref("application/json");
const schemaType = ref<SchemaType>("JSON_SCHEMA");
const schema = ref("");

const schemaTypeOptions = [
	{ label: "JSON Schema", value: "JSON_SCHEMA" },
	{ label: "Protocol Buffers", value: "PROTO" },
	{ label: "XML Schema (XSD)", value: "XSD" },
];

// Validation
const VERSION_PATTERN = /^\d+\.\d+$/;

const isVersionValid = computed(() => VERSION_PATTERN.test(version.value));

const isVersionDuplicate = computed(() => {
	if (!eventType.value) return false;
	return eventType.value.specVersions.some(
		(sv) => sv.version === version.value,
	);
});

const isFormValid = computed(() => {
	return (
		isVersionValid.value &&
		!isVersionDuplicate.value &&
		mimeType.value.trim().length > 0 &&
		schema.value.trim().length > 0
	);
});

const schemaPlaceholder = computed(() => {
	switch (schemaType.value) {
		case "JSON_SCHEMA":
			return `{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "properties": {
    "id": { "type": "string" }
  }
}`;
		case "PROTO":
			return `syntax = "proto3";

message Event {
  string id = 1;
}`;
		case "XSD":
			return `<?xml version="1.0"?>
<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
  <xs:element name="event" type="xs:string"/>
</xs:schema>`;
		default:
			return "";
	}
});

onMounted(async () => {
	const id = route.params['id'] as string;
	if (id) {
		await loadEventType(id);
	}
});

async function loadEventType(id: string) {
	loading.value = true;
	try {
		eventType.value = await eventTypesApi.get(id);
		suggestNextVersion();
	} catch {
		eventType.value = null;
	} finally {
		loading.value = false;
	}
}

function suggestNextVersion() {
	if (!eventType.value || eventType.value.specVersions.length === 0) {
		version.value = "1.0";
		return;
	}

	const versions = eventType.value.specVersions.map((sv) => {
		const parts = sv.version.split(".").map(Number);
		return { major: parts[0] ?? 0, minor: parts[1] ?? 0 };
	});

	const highestMajor = Math.max(...versions.map((v) => v.major));
	const highestMinor = Math.max(
		...versions.filter((v) => v.major === highestMajor).map((v) => v.minor),
	);

	version.value = `${highestMajor}.${highestMinor + 1}`;
}

function goBack() {
	if (eventType.value) {
		forwardFrom(`/event-types/${eventType.value.id}`);
	} else {
		returnTo("/event-types");
	}
}

async function onSubmit() {
	if (!isFormValid.value || !eventType.value) return;

	submitting.value = true;
	errorMessage.value = null;

	try {
		await eventTypesApi.addSchema(eventType.value.id, {
			version: version.value,
			mimeType: mimeType.value,
			schema: schema.value,
			schemaType: schemaType.value,
		});
		toast.success("Success", "Schema added");
		forwardFrom(`/event-types/${eventType.value.id}`);
	} catch (e) {
		errorMessage.value =
			e instanceof Error ? e.message : "Failed to add schema";
	} finally {
		submitting.value = false;
	}
}
</script>

<template>
  <div class="page-container">
    <div v-if="loading" class="loading-container">
      <ProgressSpinner strokeWidth="3" />
    </div>

    <template v-else-if="eventType">
      <header class="page-header">
        <Button
          icon="pi pi-arrow-left"
          text
          severity="secondary"
          @click="goBack"
          v-tooltip="'Back'"
        />
        <div>
          <h1 class="page-title">Add Schema Version</h1>
          <p class="page-subtitle">
            Add a new schema to <strong>{{ eventType.name }}</strong>
          </p>
        </div>
      </header>

      <form @submit.prevent="onSubmit">
        <div class="form-card">
          <section class="form-section">
            <h3 class="section-title">Version Information</h3>

            <div class="form-row">
              <div class="form-field">
                <label>Version <span class="required">*</span></label>
                <InputText
                  v-model="version"
                  placeholder="e.g., 1.0"
                  :invalid="(version && !isVersionValid) || isVersionDuplicate"
                />
                <small v-if="version && !isVersionValid" class="p-error">
                  Format: MAJOR.MINOR (e.g., 1.0)
                </small>
                <small v-else-if="isVersionDuplicate" class="p-error">
                  Version already exists
                </small>
              </div>

              <div class="form-field">
                <label>MIME Type <span class="required">*</span></label>
                <InputText v-model="mimeType" placeholder="e.g., application/json" />
              </div>
            </div>

            <div class="form-field">
              <label>Schema Type <span class="required">*</span></label>
              <Select
                v-model="schemaType"
                :options="schemaTypeOptions"
                optionLabel="label"
                optionValue="value"
                class="full-width"
              />
            </div>
          </section>

          <section class="form-section">
            <h3 class="section-title">Schema Definition</h3>

            <div class="form-field">
              <label>Schema <span class="required">*</span></label>
              <Textarea
                v-model="schema"
                :rows="15"
                class="schema-textarea"
                :placeholder="schemaPlaceholder"
              />
            </div>
          </section>

          <Message severity="info" class="info-message">
            New schemas are created in <strong>FINALISING</strong> status. Finalise when ready for
            production use.
          </Message>

          <Message v-if="errorMessage" severity="error">
            {{ errorMessage }}
          </Message>

          <div class="form-actions">
            <Button
              label="Cancel"
              icon="pi pi-times"
              severity="secondary"
              outlined
              @click="goBack"
            />
            <Button
              label="Add Schema"
              icon="pi pi-check"
              type="submit"
              :loading="submitting"
              :disabled="!isFormValid"
            />
          </div>
        </div>
      </form>
    </template>

    <Message v-else severity="error">Event type not found</Message>
  </div>
</template>

<style scoped>
.page-container {
  max-width: 900px;
}

.form-card {
  background: white;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  padding: 24px;
}

.loading-container {
  display: flex;
  justify-content: center;
  padding: 60px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 24px;
}

.form-section {
  margin-bottom: 32px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 16px;
}

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
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

.schema-textarea {
  width: 100%;
  font-family: 'SF Mono', monospace;
  font-size: 13px;
}

.info-message {
  margin-bottom: 16px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid #e2e8f0;
}

@media (max-width: 640px) {
  .form-row {
    grid-template-columns: 1fr;
  }
}
</style>
