<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import { eventTypesApi } from "@/api/event-types";
import { applicationsApi, type Application } from "@/api/applications";

const router = useRouter();

// Applications data
const applications = ref<Application[]>([]);
const filteredAppCodes = ref<string[]>([]);
const loadingApps = ref(true);

// Form state
const application = ref("");
const subdomain = ref("");
const aggregate = ref("");
const event = ref("");
const name = ref("");
const description = ref("");
const clientScoped = ref(false);

const submitting = ref(false);
const errorMessage = ref<string | null>(null);

// Load applications on mount
// Event types can belong to both applications and integrations
onMounted(async () => {
	try {
		const response = await applicationsApi.list({ activeOnly: true }); // Both applications and integrations
		applications.value = response.applications;
	} catch (e) {
		errorMessage.value = "Failed to load applications";
	} finally {
		loadingApps.value = false;
	}
});

// Filter application codes for autocomplete
function searchAppCodes(event: { query: string }) {
	const query = event.query.toLowerCase();
	filteredAppCodes.value = applications.value
		.map((app) => app.code)
		.filter((code) => code.toLowerCase().includes(query));
}

// Validation
const CODE_PATTERN = /^[a-z][a-z0-9-]*$/;

const isValidSegment = (value: string) => !value || CODE_PATTERN.test(value);

const isCodeValid = computed(() => {
	return (
		application.value &&
		subdomain.value &&
		aggregate.value &&
		event.value &&
		CODE_PATTERN.test(application.value) &&
		CODE_PATTERN.test(subdomain.value) &&
		CODE_PATTERN.test(aggregate.value) &&
		CODE_PATTERN.test(event.value)
	);
});

const isFormValid = computed(() => {
	return (
		isCodeValid.value &&
		name.value.trim().length > 0 &&
		name.value.length <= 100
	);
});

async function onSubmit() {
	if (!isFormValid.value) return;

	submitting.value = true;
	errorMessage.value = null;

	const code = `${application.value}:${subdomain.value}:${aggregate.value}:${event.value}`;

	try {
		const eventType = await eventTypesApi.create({
			code,
			name: name.value,
			description: description.value || undefined,
			clientScoped: clientScoped.value,
		});
		toast.success("Success", "Event type created");
		router.push(`/event-types/${eventType.id}`);
	} catch (e) {
		errorMessage.value =
			e instanceof Error ? e.message : "Failed to create event type";
	} finally {
		submitting.value = false;
	}
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Create Event Type</h1>
        <p class="page-subtitle">Define a new event type with its code and metadata</p>
      </div>
    </header>

    <form @submit.prevent="onSubmit">
      <div class="form-card">
        <!-- Code Builder -->
        <section class="form-section">
          <h3 class="section-title">Event Type Code</h3>
          <p class="section-description">Format: <code>app:subdomain:aggregate:event</code></p>

          <div v-if="loadingApps" class="loading-apps">
            <ProgressSpinner strokeWidth="4" style="width: 24px; height: 24px" />
            <span>Loading applications...</span>
          </div>

          <div v-else class="code-builder">
            <div class="code-segment-group">
              <label>Application <span class="required">*</span></label>
              <AutoComplete
                v-model="application"
                :suggestions="filteredAppCodes"
                @complete="searchAppCodes"
                placeholder="e.g., operant"
                :invalid="!!(application && !isValidSegment(application))"
                dropdown
              />
              <small v-if="application && !isValidSegment(application)" class="p-error">
                Lowercase letters, numbers, hyphens only
              </small>
            </div>

            <span class="code-sep">:</span>

            <div class="code-segment-group">
              <label>Subdomain</label>
              <InputText
                v-model="subdomain"
                placeholder="e.g., execution"
                :invalid="!!(subdomain && !isValidSegment(subdomain))"
              />
            </div>

            <span class="code-sep">:</span>

            <div class="code-segment-group">
              <label>Aggregate</label>
              <InputText
                v-model="aggregate"
                placeholder="e.g., trip"
                :invalid="!!(aggregate && !isValidSegment(aggregate))"
              />
            </div>

            <span class="code-sep">:</span>

            <div class="code-segment-group">
              <label>Event</label>
              <InputText
                v-model="event"
                placeholder="e.g., started"
                :invalid="!!(event && !isValidSegment(event))"
              />
            </div>
          </div>

          <!-- Code Preview -->
          <div class="code-preview" :class="{ invalid: !isCodeValid }">
            <label>Generated Code:</label>
            <div class="code-display">
              <span class="code-segment app">{{ application || 'app' }}</span>
              <span class="code-separator">:</span>
              <span class="code-segment subdomain">{{ subdomain || 'subdomain' }}</span>
              <span class="code-separator">:</span>
              <span class="code-segment aggregate">{{ aggregate || 'aggregate' }}</span>
              <span class="code-separator">:</span>
              <span class="code-segment event">{{ event || 'event' }}</span>
            </div>
          </div>
        </section>

        <!-- Metadata -->
        <section class="form-section">
          <h3 class="section-title">Metadata</h3>

          <div class="form-field">
            <label>Name <span class="required">*</span></label>
            <InputText
              v-model="name"
              placeholder="Human-friendly name"
              class="full-width"
              :invalid="name.length > 100"
            />
            <small class="char-count">{{ name.length }} / 100</small>
          </div>

          <div class="form-field">
            <label>Description</label>
            <Textarea
              v-model="description"
              placeholder="Optional description"
              :rows="3"
              class="full-width"
              :invalid="description.length > 255"
            />
            <small class="char-count">{{ description.length }} / 255</small>
          </div>

          <div class="form-field toggle-field">
            <div class="toggle-row">
              <ToggleSwitch v-model="clientScoped" inputId="clientScoped" />
              <label for="clientScoped" class="toggle-label">Client Scoped</label>
            </div>
            <small class="field-hint">
              Enable if events of this type are specific to individual clients. Client-scoped event
              types can only be used with client-scoped subscriptions.
            </small>
          </div>
        </section>

        <Message v-if="errorMessage" severity="error" class="error-message">
          {{ errorMessage }}
        </Message>

        <div class="form-actions">
          <Button
            label="Cancel"
            icon="pi pi-times"
            severity="secondary"
            outlined
            @click="router.push('/event-types')"
          />
          <Button
            label="Create Event Type"
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
  max-width: 800px;
}

.loading-apps {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  color: #64748b;
}

.app-option {
  display: flex;
  align-items: center;
  gap: 8px;
}

.app-option-name {
  font-weight: 500;
}

.app-option-code {
  background: #f1f5f9;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
  color: #64748b;
}

.placeholder-text {
  color: #94a3b8;
}

.form-card {
  background: white;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  padding: 24px;
}

.form-section {
  margin-bottom: 32px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 8px;
}

.section-description {
  color: #64748b;
  font-size: 14px;
  margin-bottom: 16px;
}

.section-description code {
  background: #f1f5f9;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: monospace;
}

.code-builder {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  flex-wrap: wrap;
}

.code-segment-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
  min-width: 120px;
}

.code-segment-group label {
  font-size: 13px;
  font-weight: 500;
  color: #475569;
}

.code-sep {
  font-size: 24px;
  color: #94a3b8;
  margin-top: 28px;
}

.code-preview {
  margin-top: 16px;
  padding: 16px;
  background: #f8fafc;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
}

.code-preview.invalid {
  opacity: 0.5;
}

.code-preview label {
  font-size: 12px;
  font-weight: 500;
  color: #64748b;
  text-transform: uppercase;
  display: block;
  margin-bottom: 8px;
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

.toggle-field {
  margin-top: 8px;
}

.toggle-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.toggle-label {
  font-weight: 500;
  cursor: pointer;
}

.field-hint {
  display: block;
  color: #64748b;
  font-size: 13px;
  margin-top: 8px;
  line-height: 1.4;
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

@media (max-width: 640px) {
  .code-builder {
    flex-direction: column;
  }

  .code-sep {
    display: none;
  }

  .code-segment-group {
    min-width: 100%;
  }
}
</style>
