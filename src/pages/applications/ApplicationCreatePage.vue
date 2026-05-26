<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, computed } from "vue";
import { useRouter } from "vue-router";
import {
	applicationsApi,
	type ApplicationType,
	type ApplicationWithServiceAccount,
} from "@/api/applications";

const router = useRouter();

// Form state
const code = ref("");
const name = ref("");
const description = ref("");
const defaultBaseUrl = ref("");
const iconUrl = ref("");
const website = ref("");
const logo = ref("");
const logoMimeType = ref("");
const type = ref<ApplicationType>("APPLICATION");

const typeOptions = [
	{ label: "Application", value: "APPLICATION" },
	{ label: "Integration", value: "INTEGRATION" },
];

// Service account credentials dialog
const showCredentialsDialog = ref(false);
const createdApplication = ref<ApplicationWithServiceAccount | null>(null);

const submitting = ref(false);
const errorMessage = ref<string | null>(null);

// Validation
const CODE_PATTERN = /^[a-z][a-z0-9-]*$/;

const isCodeValid = computed(
	() => !code.value || CODE_PATTERN.test(code.value),
);

const isFormValid = computed(() => {
	return (
		code.value &&
		CODE_PATTERN.test(code.value) &&
		name.value.trim().length > 0 &&
		name.value.length <= 100
	);
});

async function onSubmit() {
	if (!isFormValid.value) return;

	submitting.value = true;
	errorMessage.value = null;

	try {
		const application = await applicationsApi.create({
			code: code.value,
			name: name.value,
			description: description.value || undefined,
			defaultBaseUrl: defaultBaseUrl.value || undefined,
			iconUrl: iconUrl.value || undefined,
			website: website.value || undefined,
			logo: logo.value || undefined,
			logoMimeType: logoMimeType.value || undefined,
			type: type.value,
		});

		createdApplication.value = application;

		// Show credentials dialog if service account was created
		if (application.serviceAccount) {
			showCredentialsDialog.value = true;
		} else {
			toast.success("Success", "Application created");
			router.push(`/applications/${application.id}`);
		}
	} catch (e) {
		errorMessage.value =
			e instanceof Error ? e.message : "Failed to create application";
	} finally {
		submitting.value = false;
	}
}

function onCredentialsDialogClose() {
	showCredentialsDialog.value = false;
	toast.success("Success", "Application created");
	if (createdApplication.value) {
		router.push(`/applications/${createdApplication.value.id}`);
	}
}

function copyToClipboard(text: string) {
	navigator.clipboard.writeText(text);
	toast.info("Copied", "Copied to clipboard");
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <Button
        icon="pi pi-arrow-left"
        text
        severity="secondary"
        @click="router.push('/applications')"
        v-tooltip="'Back'"
      />
      <div>
        <h1 class="page-title">Create Application</h1>
        <p class="page-subtitle">Add a new application to the platform</p>
      </div>
    </header>

    <form @submit.prevent="onSubmit">
      <div class="form-card">
        <section class="form-section">
          <h3 class="section-title">Application Identity</h3>

          <div class="form-field">
            <label>Type <span class="required">*</span></label>
            <SelectButton
              v-model="type"
              :options="typeOptions"
              optionLabel="label"
              optionValue="value"
            />
            <small class="hint">
              {{
                type === 'APPLICATION'
                  ? 'User-facing application that users can log into'
                  : 'Third-party adapter or connector for integrations'
              }}
            </small>
          </div>

          <div class="form-field">
            <label>Code <span class="required">*</span></label>
            <InputText
              v-model="code"
              placeholder="e.g., operant"
              class="full-width"
              :invalid="!!(code && !isCodeValid)"
            />
            <small v-if="code && !isCodeValid" class="p-error">
              Must start with a letter, use only lowercase letters, numbers, and hyphens
            </small>
            <small v-else class="hint">
              Unique identifier for the application. Cannot be changed after creation.
            </small>
          </div>

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
            />
          </div>
        </section>

        <section class="form-section">
          <h3 class="section-title">Configuration</h3>

          <div class="form-field">
            <label>Default Base URL</label>
            <InputText
              v-model="defaultBaseUrl"
              placeholder="https://example.com"
              class="full-width"
            />
            <small class="hint">Base URL for API calls to this application</small>
          </div>

          <div class="form-field">
            <label>Icon URL</label>
            <InputText
              v-model="iconUrl"
              placeholder="https://example.com/icon.png"
              class="full-width"
            />
            <small class="hint">URL to the application's icon image</small>
          </div>

          <div class="form-field">
            <label>Website</label>
            <InputText v-model="website" placeholder="https://www.example.com" class="full-width" />
            <small class="hint">Public website URL for this application</small>
          </div>

          <div class="form-field">
            <label>Logo (SVG)</label>
            <Textarea
              v-model="logo"
              placeholder="Paste SVG content here"
              :rows="4"
              class="full-width"
            />
            <small class="hint">SVG logo content to embed in the platform</small>
          </div>

          <div class="form-field" v-if="logo">
            <label>Logo MIME Type</label>
            <InputText v-model="logoMimeType" placeholder="image/svg+xml" class="full-width" />
            <small class="hint">MIME type of the logo (e.g., image/svg+xml)</small>
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
            @click="router.push('/applications')"
          />
          <Button
            label="Create Application"
            icon="pi pi-check"
            type="submit"
            :loading="submitting"
            :disabled="!isFormValid"
          />
        </div>
      </div>
    </form>

    <!-- Service Account Credentials Dialog -->
    <Dialog
      v-model:visible="showCredentialsDialog"
      header="Service Account Created"
      :style="{ width: '550px' }"
      :modal="true"
      :closable="false"
    >
      <div class="credentials-dialog-content" v-if="createdApplication?.serviceAccount">
        <Message severity="warn" class="credentials-warning">
          Save these credentials now. The client secret will not be shown again.
        </Message>

        <div class="credential-item">
          <label>Client ID</label>
          <div class="credential-value">
            <code>{{ createdApplication.serviceAccount.oauthClient.clientId }}</code>
            <Button
              icon="pi pi-copy"
              text
              size="small"
              @click="copyToClipboard(createdApplication.serviceAccount.oauthClient.clientId)"
            />
          </div>
        </div>

        <div class="credential-item">
          <label>Client Secret</label>
          <div class="credential-value">
            <code>{{ createdApplication.serviceAccount.oauthClient.clientSecret }}</code>
            <Button
              icon="pi pi-copy"
              text
              size="small"
              @click="copyToClipboard(createdApplication.serviceAccount.oauthClient.clientSecret)"
            />
          </div>
        </div>

        <div class="credential-item">
          <label>Service Account</label>
          <div class="credential-value">
            <span>{{ createdApplication.serviceAccount.name }}</span>
          </div>
        </div>
      </div>

      <template #footer>
        <Button
          label="I've saved the credentials"
          icon="pi pi-check"
          @click="onCredentialsDialogClose"
        />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.page-container {
  max-width: 700px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 24px;
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
  margin-bottom: 16px;
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

.hint {
  display: block;
  font-size: 12px;
  color: #64748b;
  margin-top: 4px;
}

.char-count {
  display: block;
  text-align: right;
  font-size: 12px;
  color: #94a3b8;
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

/* Credentials Dialog */
.credentials-dialog-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.credentials-warning {
  margin-bottom: 8px;
}

.credential-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.credential-item > label {
  font-size: 12px;
  font-weight: 500;
  color: #64748b;
  text-transform: uppercase;
}

.credential-value {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 8px 12px;
}

.credential-value code {
  font-family: 'JetBrains Mono', monospace;
  font-size: 13px;
  flex: 1;
  word-break: break-all;
}
</style>
