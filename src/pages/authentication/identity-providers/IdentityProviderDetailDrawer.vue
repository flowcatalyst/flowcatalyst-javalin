<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, computed, watch } from "vue";
import { useRoute } from "vue-router";
import {
	identityProvidersApi,
	type IdentityProvider,
} from "@/api/identity-providers";
import { getErrorMessage } from "@/utils/errors";
import EntityDrawer from "@/components/drawer/EntityDrawer.vue";
import { useDrawerRoute } from "@/composables/useDrawerRoute";
import { useDirtyForm } from "@/composables/useDirtyForm";

const emit = defineEmits<{
	changed: [];
}>();

const route = useRoute();

const isEditing = ref(false);

const provider = ref<IdentityProvider | null>(null);
const loading = ref(true);
const saving = ref(false);
const loadError = ref<string | null>(null);
const saveError = ref<string | null>(null);

// Edit mode
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

const { dirty, markClean, reset: resetDirty } = useDirtyForm(() => ({
	...editForm.value,
}));

const drawer = ref<InstanceType<typeof EntityDrawer> | null>(null);
const { id, goToList } = useDrawerRoute({
	listPath: "/authentication/identity-providers",
	dirty: computed(() => isEditing.value && dirty.value),
});

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

// Reactive param: the drawer instance is reused when switching between rows.
watch(
	id,
	async (value) => {
		if (!value) return;
		await loadProvider(value);
		if (provider.value && route.query["edit"] === "true") {
			startEditing();
		}
	},
	{ immediate: true },
);

async function loadProvider(providerId: string) {
	loading.value = true;
	loadError.value = null;
	saveError.value = null;
	isEditing.value = false;
	resetDirty();
	showDeleteDialog.value = false;
	newAllowedDomain.value = "";
	try {
		provider.value = await identityProvidersApi.get(providerId);
		resetEditForm();
	} catch (e) {
		provider.value = null;
		loadError.value =
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
	saveError.value = null;
	isEditing.value = true;
	markClean();
}

function cancelEditing() {
	resetEditForm();
	saveError.value = null;
	isEditing.value = false;
	resetDirty();
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
	saveError.value = null;

	try {
		const updateData: Record<string, unknown> = {
			name: editForm.value.name.trim(),
			allowedEmailDomains: editForm.value.allowedEmailDomains,
		};

		if (provider.value.type === "OIDC") {
			updateData["oidcIssuerUrl"] = editForm.value.oidcIssuerUrl.trim() || null;
			updateData["oidcClientId"] = editForm.value.oidcClientId.trim();
			updateData["oidcMultiTenant"] = editForm.value.oidcMultiTenant;
			updateData["oidcIssuerPattern"] =
				editForm.value.oidcIssuerPattern.trim() || null;
			if (editForm.value.oidcClientSecretRef.trim()) {
				updateData["oidcClientSecretRef"] =
					editForm.value.oidcClientSecretRef.trim();
			}
		}

		const updated = await identityProvidersApi.update(
			provider.value.id,
			updateData,
		);
		provider.value = updated;
		isEditing.value = false;
		resetDirty();
		toast.success("Success", "Identity provider updated successfully");
		emit("changed");
	} catch (e: unknown) {
		saveError.value = getErrorMessage(e, "Failed to update identity provider");
	} finally {
		saving.value = false;
	}
}

async function deleteProvider() {
	if (!provider.value) return;

	deleteLoading.value = true;

	try {
		await identityProvidersApi.delete(provider.value.id);
		toast.success(
			"Success",
			`Identity provider "${provider.value.name}" deleted`,
		);
		emit("changed");
		showDeleteDialog.value = false;
		isEditing.value = false;
		void drawer.value?.close(true);
	} catch {
		showDeleteDialog.value = false;
	} finally {
		deleteLoading.value = false;
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
  <EntityDrawer
    ref="drawer"
    :title="provider?.name || 'Identity Provider'"
    :subtitle="provider ? provider.code : undefined"
    :loading="loading"
    :error="loadError"
    :dirty="isEditing && dirty"
    @close="goToList()"
  >
    <template v-if="provider && !isEditing" #header-extra>
      <Tag :value="provider.type" :severity="getTypeSeverity(provider.type)" />
    </template>

    <template v-if="provider">
      <Message v-if="saveError" severity="error" class="save-error" :closable="true" @close="saveError = null">
        {{ saveError }}
      </Message>

      <!-- Provider -->
      <FcFormSection title="Provider" flat>
        <!-- View mode -->
        <div v-if="!isEditing" class="fc-detail-grid">
          <FcDetailField label="Name" :value="provider.name" />
          <FcDetailField label="Code">
            <code class="code-value">{{ provider.code }}</code>
          </FcDetailField>
          <FcDetailField label="Type" :value="provider.type" />
          <FcDetailField label="Created" :value="formatDate(provider.createdAt)" />
          <FcDetailField label="Last Updated" :value="formatDate(provider.updatedAt)" />
        </div>

        <!-- Edit mode -->
        <div v-else class="fc-form-grid">
          <FcDetailField label="Code">
            <code class="code-value">{{ provider.code }}</code>
            <small class="fc-field-help">Code cannot be changed</small>
          </FcDetailField>
          <FcDetailField label="Type">
            {{ provider.type }}
            <small class="fc-field-help">Type cannot be changed</small>
          </FcDetailField>

          <FcFormField label="Name" required span>
            <template #default="{ id: fieldId }">
              <InputText :id="fieldId" v-model="editForm.name" />
            </template>
          </FcFormField>
        </div>
      </FcFormSection>

      <!-- OIDC Configuration -->
      <FcFormSection v-if="provider.type === 'OIDC'" title="OIDC Configuration" flat>
        <!-- View mode -->
        <div v-if="!isEditing" class="field-stack">
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
        </div>

        <!-- Edit mode -->
        <div v-else class="field-stack">
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

          <SecretRefInput
            v-model="editForm.oidcClientSecretRef"
            label="Client Secret"
            :help-text="
              provider.hasClientSecret
                ? 'Current secret is configured. Enter a new value to replace it, or leave blank to keep it.'
                : 'Enter the client secret'
            "
          />
        </div>
      </FcFormSection>

      <!-- Allowed Email Domains -->
      <FcFormSection title="Allowed Email Domains" flat>
        <!-- View mode -->
        <template v-if="!isEditing">
          <div v-if="provider.allowedEmailDomains?.length > 0" class="domain-list">
            <Chip
              v-for="domain in provider.allowedEmailDomains"
              :key="domain"
              :label="domain"
            />
          </div>
          <span v-else class="text-muted">All domains allowed</span>
        </template>

        <!-- Edit mode -->
        <div v-else class="field">
          <div class="domain-input">
            <InputText
              v-model="newAllowedDomain"
              placeholder="example.com"
              class="flex-grow"
              @keyup.enter="addAllowedDomain"
            />
            <Button
              icon="pi pi-plus"
              :disabled="!newAllowedDomain.trim()"
              @click="addAllowedDomain"
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
      </FcFormSection>
    </template>

    <template v-if="provider && !loading && !loadError" #footer>
      <template v-if="!isEditing">
        <Button
          label="Delete"
          icon="pi pi-trash"
          severity="danger"
          text
          @click="showDeleteDialog = true"
        />
        <Button label="Edit" icon="pi pi-pencil" @click="startEditing" />
      </template>
      <FcFormActions v-else :bordered="false">
        <Button v-if="dirty" label="Discard" text :disabled="saving" @click="cancelEditing" />
        <Button
          label="Save Changes"
          icon="pi pi-check"
          :loading="saving"
          :disabled="!dirty || !isValid"
          @click="saveChanges"
        />
      </FcFormActions>
    </template>
  </EntityDrawer>

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
      <Button label="Cancel" text :disabled="deleteLoading" @click="showDeleteDialog = false" />
      <Button
        label="Delete"
        icon="pi pi-trash"
        severity="danger"
        :loading="deleteLoading"
        @click="deleteProvider"
      />
    </template>
  </Dialog>
</template>

<style scoped>
.save-error {
  margin-bottom: 16px;
}

.code-value {
  background: #f1f5f9;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 13px;
  font-family: monospace;
}

.field-stack {
  display: flex;
  flex-direction: column;
  gap: 20px;
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
