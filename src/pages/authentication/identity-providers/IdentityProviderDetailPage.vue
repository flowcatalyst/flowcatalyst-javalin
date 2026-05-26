<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, computed, onMounted } from "vue";
import { useRoute } from "vue-router";
import {
	identityProvidersApi,
	type IdentityProvider,
} from "@/api/identity-providers";
import { getErrorMessage } from "@/utils/errors";
import { useReturnTo } from "@/composables/useReturnTo";

const route = useRoute();
const { returnTo } = useReturnTo();

const provider = ref<IdentityProvider | null>(null);
const loading = ref(true);
const saving = ref(false);
const error = ref<string | null>(null);

// Edit mode
const isEditing = ref(false);
const editForm = ref({
	name: "",
	oidcIssuerUrl: "",
	oidcClientId: "",
	oidcClientSecretRef: "",
	oidcMultiTenant: false,
	oidcIssuerPattern: "",
	allowedEmailDomains: [] as string[],
});
const newAllowedDomain = ref("");

// Delete dialog
const showDeleteDialog = ref(false);
const deleteLoading = ref(false);

const isValid = computed(() => {
	if (!editForm.value.name.trim()) return false;
	if (provider.value?.type === "OIDC") {
		if (!editForm.value.oidcIssuerUrl.trim()) return false; // Always required for OIDC
		if (!editForm.value.oidcClientId.trim()) return false;
	}
	return true;
});

onMounted(async () => {
	await loadProvider();
});

async function loadProvider() {
	loading.value = true;
	error.value = null;
	try {
		const id = route.params['id'] as string;
		provider.value = await identityProvidersApi.get(id);
		resetEditForm();
	} catch (e) {
		error.value =
			e instanceof Error ? e.message : "Failed to load identity provider";
	} finally {
		loading.value = false;
	}
}

function resetEditForm() {
	if (provider.value) {
		editForm.value = {
			name: provider.value.name,
			oidcIssuerUrl: provider.value.oidcIssuerUrl || "",
			oidcClientId: provider.value.oidcClientId || "",
			oidcClientSecretRef: "",
			oidcMultiTenant: provider.value.oidcMultiTenant,
			oidcIssuerPattern: provider.value.oidcIssuerPattern || "",
			allowedEmailDomains: [...(provider.value.allowedEmailDomains || [])],
		};
	}
}

function startEditing() {
	resetEditForm();
	isEditing.value = true;
}

function cancelEditing() {
	resetEditForm();
	isEditing.value = false;
}

function addAllowedDomain() {
	const domain = newAllowedDomain.value.trim().toLowerCase();
	if (domain && !editForm.value.allowedEmailDomains.includes(domain)) {
		if (domain.match(/^[a-z0-9][a-z0-9.-]*\.[a-z]{2,}$/)) {
			editForm.value.allowedEmailDomains.push(domain);
			newAllowedDomain.value = "";
		} else {
			toast.error("Invalid Domain", "Please enter a valid domain name");
		}
	}
}

function removeAllowedDomain(domain: string) {
	editForm.value.allowedEmailDomains =
		editForm.value.allowedEmailDomains.filter((d) => d !== domain);
}

async function saveChanges() {
	if (!provider.value || !isValid.value) return;

	saving.value = true;
	error.value = null;

	try {
		const updateData: Record<string, unknown> = {
			name: editForm.value.name.trim(),
			allowedEmailDomains: editForm.value.allowedEmailDomains,
		};

		if (provider.value.type === "OIDC") {
			updateData['oidcIssuerUrl'] = editForm.value.oidcIssuerUrl.trim() || null;
			updateData['oidcClientId'] = editForm.value.oidcClientId.trim();
			updateData['oidcMultiTenant'] = editForm.value.oidcMultiTenant;
			updateData['oidcIssuerPattern'] =
				editForm.value.oidcIssuerPattern.trim() || null;
			if (editForm.value.oidcClientSecretRef.trim()) {
				updateData['oidcClientSecretRef'] =
					editForm.value.oidcClientSecretRef.trim();
			}
		}

		const updated = await identityProvidersApi.update(
			provider.value.id,
			updateData,
		);
		provider.value = updated;
		isEditing.value = false;
		toast.success("Success", "Identity provider updated successfully");
	} catch (e: unknown) {
		error.value = getErrorMessage(e, "Failed to update identity provider");
	} finally {
		saving.value = false;
	}
}

async function deleteProvider() {
	if (!provider.value) return;

	deleteLoading.value = true;

	try {
		await identityProvidersApi.delete(provider.value.id);
		toast.success("Success", `Identity provider "${provider.value.name}" deleted`);
		returnTo("/authentication/identity-providers");
	} catch (e: unknown) {
	} finally {
		deleteLoading.value = false;
		showDeleteDialog.value = false;
	}
}

function formatDate(dateString: string) {
	return new Date(dateString).toLocaleString();
}

function getTypeSeverity(type: string) {
	return type === "OIDC" ? "info" : "secondary";
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <Button
          icon="pi pi-arrow-left"
          text
          class="back-button"
          @click="returnTo('/authentication/identity-providers')"
        />
        <h1 class="page-title">{{ provider?.name || 'Identity Provider Details' }}</h1>
        <p class="page-subtitle" v-if="provider">
          <code class="provider-code">{{ provider.code }}</code>
        </p>
      </div>
      <div v-if="provider && !isEditing" class="header-actions">
        <Button
          label="Delete"
          icon="pi pi-trash"
          severity="danger"
          text
          @click="showDeleteDialog = true"
        />
        <Button label="Edit" icon="pi pi-pencil" @click="startEditing" />
      </div>
    </header>

    <div v-if="loading" class="loading-container">
      <ProgressSpinner strokeWidth="3" />
    </div>

    <Message v-else-if="error" severity="error" class="error-message">{{ error }}</Message>

    <template v-else-if="provider">
      <div class="fc-card">
        <div class="card-header">
          <h2 class="card-title">Provider Configuration</h2>
          <div v-if="!isEditing" class="status-badges">
            <Tag :value="provider.type" :severity="getTypeSeverity(provider.type)" />
          </div>
        </div>

        <div class="form-content">
          <!-- View Mode -->
          <template v-if="!isEditing">
            <div class="field-group">
              <label>Name</label>
              <span class="field-value">{{ provider.name }}</span>
            </div>

            <div class="field-group">
              <label>Code</label>
              <span class="field-value">
                <code class="code-value">{{ provider.code }}</code>
              </span>
            </div>

            <div class="field-group">
              <label>Type</label>
              <span class="field-value">{{ provider.type }}</span>
            </div>

            <template v-if="provider.type === 'OIDC'">
              <div class="field-group">
                <label>Multi-Tenant</label>
                <span class="field-value">
                  <i
                    :class="
                      provider.oidcMultiTenant
                        ? 'pi pi-check text-success'
                        : 'pi pi-times text-muted'
                    "
                  />
                  {{ provider.oidcMultiTenant ? 'Yes' : 'No' }}
                </span>
              </div>

              <div class="field-group">
                <label>Issuer URL</label>
                <span class="field-value">{{ provider.oidcIssuerUrl || '-' }}</span>
              </div>

              <div
                class="field-group"
                v-if="provider.oidcMultiTenant && provider.oidcIssuerPattern"
              >
                <label>Issuer Pattern</label>
                <span class="field-value">{{ provider.oidcIssuerPattern }}</span>
                <small class="text-muted">Auto-derived from Issuer URL if not set</small>
              </div>

              <div class="field-group">
                <label>Client ID</label>
                <span class="field-value">
                  <code class="code-value">{{ provider.oidcClientId || '-' }}</code>
                </span>
              </div>

              <div class="field-group">
                <label>Client Secret</label>
                <span class="field-value">
                  <i
                    :class="
                      provider.hasClientSecret
                        ? 'pi pi-check text-success'
                        : 'pi pi-times text-muted'
                    "
                  />
                  {{ provider.hasClientSecret ? 'Configured' : 'Not configured' }}
                </span>
              </div>
            </template>

            <div class="field-group">
              <label>Allowed Email Domains</label>
              <div v-if="provider.allowedEmailDomains?.length > 0" class="domain-list">
                <Chip
                  v-for="domain in provider.allowedEmailDomains"
                  :key="domain"
                  :label="domain"
                />
              </div>
              <span v-else class="text-muted">All domains allowed</span>
            </div>

            <div class="field-group">
              <label>Created</label>
              <span class="field-value">{{ formatDate(provider.createdAt) }}</span>
            </div>

            <div class="field-group">
              <label>Last Updated</label>
              <span class="field-value">{{ formatDate(provider.updatedAt) }}</span>
            </div>
          </template>

          <!-- Edit Mode -->
          <template v-else>
            <div class="field">
              <label for="name">Name *</label>
              <InputText id="name" v-model="editForm.name" class="w-full" />
            </div>

            <template v-if="provider.type === 'OIDC'">
              <div class="field checkbox-field">
                <Checkbox id="multiTenant" v-model="editForm.oidcMultiTenant" :binary="true" />
                <label for="multiTenant" class="checkbox-label">Multi-Tenant Mode</label>
              </div>

              <div class="field">
                <label for="issuerUrl">Issuer URL *</label>
                <InputText
                  id="issuerUrl"
                  v-model="editForm.oidcIssuerUrl"
                  :placeholder="
                    editForm.oidcMultiTenant
                      ? 'https://login.microsoftonline.com/common/v2.0'
                      : 'https://login.example.com'
                  "
                  class="w-full"
                />
                <small class="field-help">
                  {{
                    editForm.oidcMultiTenant
                      ? 'Base URL for authorization/token endpoints (e.g., .../common/v2.0)'
                      : 'The OpenID Connect issuer URL'
                  }}
                </small>
              </div>

              <div v-if="editForm.oidcMultiTenant" class="field">
                <label for="issuerPattern">Issuer Pattern</label>
                <InputText
                  id="issuerPattern"
                  v-model="editForm.oidcIssuerPattern"
                  placeholder="https://login.microsoftonline.com/{tenantId}/v2.0"
                  class="w-full"
                />
                <small class="field-help">
                  Optional. Pattern for validating token issuer. Use {tenantId} as placeholder.
                  Leave empty to auto-derive from Issuer URL.
                </small>
              </div>

              <div class="field">
                <label for="clientId">Client ID *</label>
                <InputText id="clientId" v-model="editForm.oidcClientId" class="w-full" />
              </div>

              <div class="field">
                <label for="clientSecret">Client Secret</label>
                <InputText
                  id="clientSecret"
                  v-model="editForm.oidcClientSecretRef"
                  type="password"
                  placeholder="Leave blank to keep current"
                  class="w-full"
                />
                <small class="field-help">
                  {{
                    provider.hasClientSecret
                      ? 'Current secret is configured. Enter a new value to replace it.'
                      : 'Enter the client secret'
                  }}
                </small>
              </div>
            </template>

            <div class="field">
              <label>Allowed Email Domains</label>
              <div class="domain-input">
                <InputText
                  v-model="newAllowedDomain"
                  placeholder="example.com"
                  class="flex-grow"
                  @keyup.enter="addAllowedDomain"
                />
                <Button
                  icon="pi pi-plus"
                  @click="addAllowedDomain"
                  :disabled="!newAllowedDomain.trim()"
                />
              </div>
              <div v-if="editForm.allowedEmailDomains.length > 0" class="domain-list">
                <Chip
                  v-for="domain in editForm.allowedEmailDomains"
                  :key="domain"
                  :label="domain"
                  removable
                  @remove="removeAllowedDomain(domain)"
                />
              </div>
              <small class="field-help">
                Restrict which email domains can authenticate. Leave empty to allow all domains.
              </small>
            </div>

            <div class="form-actions">
              <Button label="Cancel" text @click="cancelEditing" :disabled="saving" />
              <Button
                label="Save Changes"
                icon="pi pi-check"
                @click="saveChanges"
                :loading="saving"
                :disabled="!isValid"
              />
            </div>
          </template>
        </div>
      </div>
    </template>

    <!-- Delete Confirmation Dialog -->
    <Dialog
      v-model:visible="showDeleteDialog"
      header="Delete Identity Provider"
      modal
      :style="{ width: '450px' }"
    >
      <div class="dialog-content">
        <p>
          Are you sure you want to delete the identity provider
          <strong>{{ provider?.name }}</strong
          >?
        </p>

        <Message severity="warn" :closable="false">
          Email domain mappings using this provider will need to be updated.
        </Message>
      </div>

      <template #footer>
        <Button label="Cancel" text @click="showDeleteDialog = false" :disabled="deleteLoading" />
        <Button
          label="Delete"
          icon="pi pi-trash"
          severity="danger"
          @click="deleteProvider"
          :loading="deleteLoading"
        />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.back-button {
  margin-right: 8px;
}

.provider-code {
  background: #f1f5f9;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 13px;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.loading-container {
  display: flex;
  justify-content: center;
  padding: 60px;
}

.error-message {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid #e2e8f0;
}

.card-title {
  font-size: 18px;
  font-weight: 600;
  margin: 0;
  color: #1e293b;
}

.status-badges {
  display: flex;
  gap: 8px;
}

.form-content {
  display: flex;
  flex-direction: column;
  gap: 20px;
  max-width: 600px;
}

.field-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field-group label {
  font-weight: 500;
  color: #64748b;
  font-size: 13px;
}

.field-value {
  color: #1e293b;
  font-size: 15px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field label {
  font-weight: 500;
  color: #334155;
}

.field-help {
  color: #64748b;
  font-size: 12px;
}

.checkbox-field {
  flex-direction: row;
  align-items: center;
  gap: 8px;
}

.checkbox-label {
  margin: 0;
  cursor: pointer;
}

.domain-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.domain-input {
  display: flex;
  gap: 8px;
}

.flex-grow {
  flex: 1;
}

.code-value {
  background: #f1f5f9;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 13px;
  font-family: monospace;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid #e2e8f0;
}

.dialog-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.text-muted {
  color: #94a3b8;
}

.text-success {
  color: #22c55e;
}

.w-full {
  width: 100%;
}
</style>
