<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, computed, watch } from "vue";
import { useRoute } from "vue-router";
import {
	emailDomainMappingsApi,
	type EmailDomainMapping,
	type ScopeType,
	type TwoFactorMethod,
} from "@/api/email-domain-mappings";
import {
	identityProvidersApi,
	type IdentityProvider,
} from "@/api/identity-providers";
import { clientsApi, type Client } from "@/api/clients";
import { rolesApi, type Role } from "@/api/roles";
import { getErrorMessage } from "@/utils/errors";
import EntityDrawer from "@/components/drawer/EntityDrawer.vue";
import { useDrawerRoute } from "@/composables/useDrawerRoute";

const emit = defineEmits<{
	changed: [];
}>();

const isEditing = ref(false);

const drawer = ref<InstanceType<typeof EntityDrawer> | null>(null);
const { id, goToList } = useDrawerRoute({
	listPath: "/authentication/email-domain-mappings",
	dirty: isEditing,
});

const mapping = ref<EmailDomainMapping | null>(null);
const provider = ref<IdentityProvider | null>(null);
const clients = ref<Client[]>([]);
const allRoles = ref<Role[]>([]);
const loading = ref(true);
const saving = ref(false);
const loadError = ref<string | null>(null);
const saveError = ref<string | null>(null);

// Role picker state: [availableRoles, selectedRoles]
const rolePickerModel = ref<[Role[], Role[]]>([[], []]);

const editForm = ref({
	scopeType: "CLIENT" as ScopeType,
	primaryClientId: null as string | null,
	requiredOidcTenantId: "" as string,
	syncRolesFromIdp: false,
	require2fa: false,
	allowed2faMethods: [] as TwoFactorMethod[],
	rememberDeviceEnabled: false,
	rememberDeviceDays: 30,
});

// 2FA only applies to internal-auth domains: the linked provider must be loaded
// and not OIDC. Hidden for OIDC-linked domains.
const show2faControls = computed(
	() => !!provider.value && provider.value.type !== "OIDC",
);

function toggle2faMethod(method: TwoFactorMethod, on: boolean) {
	const set = new Set(editForm.value.allowed2faMethods);
	if (on) set.add(method);
	else set.delete(method);
	editForm.value.allowed2faMethods = [...set];
}

// Client autocomplete
const filteredClients = ref<Client[]>([]);
const selectedPrimaryClient = ref<Client | null>(null);

// Delete dialog
const showDeleteDialog = ref(false);
const deleteLoading = ref(false);

const scopeTypeOptions = [
	{
		label: "Anchor",
		value: "ANCHOR",
		description: "Platform admin - access to all clients",
	},
	{
		label: "Partner",
		value: "PARTNER",
		description: "Partner user - access to multiple clients",
	},
	{
		label: "Client",
		value: "CLIENT",
		description: "Client user - bound to a single client",
	},
];

const isValid = computed(() => {
	if (
		editForm.value.scopeType === "CLIENT" &&
		editForm.value.primaryClientId == null
	) {
		return false;
	}
	if (isOidcMultiTenant.value && !editForm.value.requiredOidcTenantId.trim()) {
		return false;
	}
	return true;
});

// Reactive param: the drawer instance is reused when switching between rows.
const route = useRoute();
watch(
	id,
	async (value) => {
		if (!value) return;
		await loadData(value);
		if (mapping.value && route.query["edit"] === "true") {
			startEditing();
		}
	},
	{ immediate: true },
);

async function loadData(mappingId: string) {
	loading.value = true;
	loadError.value = null;
	saveError.value = null;
	isEditing.value = false;
	try {
		const [mappingData, clientsResponse, rolesResponse] = await Promise.all([
			emailDomainMappingsApi.get(mappingId),
			clientsApi.list(),
			rolesApi.list(),
		]);
		mapping.value = mappingData;
		clients.value = clientsResponse.clients;
		allRoles.value = rolesResponse.items;

		// Load the identity provider
		provider.value = await identityProvidersApi.get(
			mappingData.identityProviderId,
		);

		resetEditForm();
	} catch (e) {
		loadError.value =
			e instanceof Error ? e.message : "Failed to load email domain mapping";
	} finally {
		loading.value = false;
	}
}

function resetEditForm() {
	if (mapping.value) {
		editForm.value = {
			// Wire values are ANCHOR | PARTNER | CLIENT; the spec types them as
			// plain string, so narrow at the form boundary.
			scopeType: mapping.value.scopeType as ScopeType,
			primaryClientId: mapping.value.primaryClientId || null,
			requiredOidcTenantId: mapping.value.requiredOidcTenantId || "",
			syncRolesFromIdp: mapping.value.syncRolesFromIdp ?? false,
			require2fa: mapping.value.require2fa ?? false,
			// Wire values are TOTP | EMAIL_PIN; spec types them as plain string.
			allowed2faMethods: [
				...(mapping.value.allowed2faMethods ?? []),
			] as TwoFactorMethod[],
			rememberDeviceEnabled: mapping.value.rememberDeviceEnabled ?? false,
			rememberDeviceDays: mapping.value.rememberDeviceDays ?? 30,
		};
		if (mapping.value.primaryClientId) {
			selectedPrimaryClient.value =
				clients.value.find((c) => c.id === mapping.value?.primaryClientId) ||
				null;
		} else {
			selectedPrimaryClient.value = null;
		}

		// Set up role picker
		const allowedRoleIds = new Set(mapping.value.allowedRoleIds || []);
		const selectedRoles = allRoles.value.filter((r) =>
			allowedRoleIds.has(r.id),
		);
		const availableRoles = allRoles.value.filter(
			(r) => !allowedRoleIds.has(r.id),
		);
		rolePickerModel.value = [availableRoles, selectedRoles];
	}
}

const isOidcMultiTenant = computed(() => {
	return provider.value?.oidcMultiTenant === true;
});

const isExternalIdp = computed(() => {
	return provider.value?.type === "OIDC";
});

const showRolePicker = computed(() => {
	return isExternalIdp.value && editForm.value.scopeType !== "ANCHOR";
});

const showRoleDisplay = computed(() => {
	return isExternalIdp.value && mapping.value?.scopeType !== "ANCHOR";
});

function getAllowedRoleNames(): string[] {
	if (!mapping.value?.allowedRoleIds?.length) return [];
	return mapping.value.allowedRoleIds.map((roleId) => {
		const role = allRoles.value.find((r) => r.id === roleId);
		return role?.displayName || role?.name || roleId;
	});
}

function startEditing() {
	resetEditForm();
	saveError.value = null;
	isEditing.value = true;
}

function cancelEditing() {
	resetEditForm();
	saveError.value = null;
	isEditing.value = false;
}

function searchClients(event: { query: string }) {
	const query = event.query.toLowerCase();
	filteredClients.value = clients.value.filter(
		(c) =>
			c.name.toLowerCase().includes(query) ||
			c.identifier.toLowerCase().includes(query),
	);
}

function onClientSelect(event: { value: Client }) {
	editForm.value.primaryClientId = event.value.id;
}

function clearPrimaryClient() {
	editForm.value.primaryClientId = null;
	selectedPrimaryClient.value = null;
}

async function saveChanges() {
	if (!mapping.value || !isValid.value) return;

	saving.value = true;
	saveError.value = null;

	try {
		const updateData: Record<string, unknown> = {
			scopeType: editForm.value.scopeType,
		};

		if (editForm.value.scopeType === "CLIENT") {
			updateData["primaryClientId"] = editForm.value.primaryClientId;
		} else if (editForm.value.scopeType === "ANCHOR") {
			updateData["primaryClientId"] = null;
		}

		// Include tenant ID (empty string clears it)
		if (isOidcMultiTenant.value) {
			updateData["requiredOidcTenantId"] =
				editForm.value.requiredOidcTenantId || "";
		}

		// Include allowed roles (send the selected roles' IDs)
		if (showRolePicker.value) {
			updateData["allowedRoleIds"] = rolePickerModel.value[1].map((r) => r.id);
		}

		// Include syncRolesFromIdp for external IDPs with non-ANCHOR scope
		if (showRolePicker.value) {
			updateData["syncRolesFromIdp"] = editForm.value.syncRolesFromIdp;
		}

		// 2FA settings (internal-auth domains only).
		if (show2faControls.value) {
			updateData["require2fa"] = editForm.value.require2fa;
			updateData["allowed2faMethods"] = editForm.value.require2fa
				? editForm.value.allowed2faMethods
				: [];
			updateData["rememberDeviceEnabled"] =
				editForm.value.rememberDeviceEnabled;
			updateData["rememberDeviceDays"] = editForm.value.rememberDeviceDays;
		}

		const mappingId = mapping.value.id;
		await emailDomainMappingsApi.update(mappingId, updateData);
		// PUT returns 204 No Content (empty body), so re-fetch the mapping to
		// refresh the view with the saved values.
		const refreshed = await emailDomainMappingsApi.get(mappingId);
		mapping.value = refreshed;

		// Update the selected client display
		if (refreshed.primaryClientId) {
			selectedPrimaryClient.value =
				clients.value.find((c) => c.id === refreshed.primaryClientId) || null;
		} else {
			selectedPrimaryClient.value = null;
		}

		isEditing.value = false;
		toast.success("Success", "Email domain mapping updated successfully");
		emit("changed");
	} catch (e: unknown) {
		saveError.value = getErrorMessage(e, "Failed to update mapping");
	} finally {
		saving.value = false;
	}
}

async function deleteMapping() {
	if (!mapping.value) return;

	deleteLoading.value = true;

	try {
		await emailDomainMappingsApi.delete(mapping.value.id);
		toast.success(
			"Success",
			`Email domain mapping for "${mapping.value.emailDomain}" deleted`,
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

function getScopeTypeSeverity(scopeType: string) {
	switch (scopeType) {
		case "ANCHOR":
			return "danger";
		case "PARTNER":
			return "warn";
		case "CLIENT":
			return "info";
		default:
			return "secondary";
	}
}

function getPrimaryClientName(): string {
	if (!mapping.value?.primaryClientId) return "";
	const client = clients.value.find(
		(c) => c.id === mapping.value?.primaryClientId,
	);
	return client?.name || "Unknown";
}
</script>

<template>
  <EntityDrawer
    ref="drawer"
    :title="mapping?.emailDomain || 'Email Domain Mapping'"
    :subtitle="provider ? `Identity Provider: ${provider.name}` : undefined"
    :loading="loading"
    :error="loadError"
    :dirty="isEditing"
    @close="goToList()"
  >
    <template v-if="mapping && !isEditing" #header-extra>
      <Tag :value="mapping.scopeType" :severity="getScopeTypeSeverity(mapping.scopeType)" />
    </template>

    <template v-if="mapping">
      <Message v-if="saveError" severity="error" class="save-error" :closable="true" @close="saveError = null">
        {{ saveError }}
      </Message>

      <!-- Mapping -->
      <FcFormSection title="Mapping" flat>
        <!-- View mode -->
        <div v-if="!isEditing" class="fc-detail-grid">
          <FcDetailField label="Email Domain">
            <span class="domain-value">{{ mapping.emailDomain }}</span>
          </FcDetailField>
          <FcDetailField label="Identity Provider" :value="provider?.name || 'Unknown'" />
          <FcDetailField label="Scope Type" :value="mapping.scopeType" />
          <FcDetailField
            v-if="mapping.scopeType === 'CLIENT'"
            label="Primary Client"
            :value="getPrimaryClientName()"
          />
          <FcDetailField v-if="isOidcMultiTenant" label="Required OIDC Tenant ID" span>
            <code v-if="mapping.requiredOidcTenantId" class="tenant-id">{{
              mapping.requiredOidcTenantId
            }}</code>
            <span v-else class="muted">Not set</span>
          </FcDetailField>
          <FcDetailField label="Created" :value="formatDate(mapping.createdAt)" />
          <FcDetailField label="Last Updated" :value="formatDate(mapping.updatedAt)" />
        </div>

        <!-- Edit mode -->
        <div v-else class="fc-form-grid">
          <FcDetailField label="Email Domain">
            <span class="domain-value">{{ mapping.emailDomain }}</span>
            <small class="fc-field-help">Email domain cannot be changed</small>
          </FcDetailField>
          <FcDetailField label="Identity Provider">
            {{ provider?.name || 'Unknown' }}
            <small class="fc-field-help">Identity provider cannot be changed</small>
          </FcDetailField>

          <FcFormField label="Scope Type" required>
            <template #default="{ id: fieldId }">
              <Select
                :id="fieldId"
                v-model="editForm.scopeType"
                :options="scopeTypeOptions"
                optionLabel="label"
                optionValue="value"
              >
                <template #option="slotProps">
                  <div class="type-option">
                    <span class="type-label">{{ slotProps.option.label }}</span>
                    <span class="type-description">{{ slotProps.option.description }}</span>
                  </div>
                </template>
              </Select>
            </template>
          </FcFormField>

          <FcFormField
            v-if="editForm.scopeType === 'CLIENT'"
            label="Primary Client"
            required
            help="Users from this domain will be bound to this client"
          >
            <template #default="{ id: fieldId }">
              <div class="client-select">
                <AutoComplete
                  :id="fieldId"
                  v-model="selectedPrimaryClient"
                  :suggestions="filteredClients"
                  optionLabel="name"
                  placeholder="Search for a client..."
                  @complete="searchClients"
                  @item-select="onClientSelect"
                />
                <Button
                  v-if="selectedPrimaryClient"
                  icon="pi pi-times"
                  text
                  @click="clearPrimaryClient"
                />
              </div>
            </template>
          </FcFormField>

          <FcFormField
            v-if="isOidcMultiTenant"
            label="Required OIDC Tenant ID"
            required
            span
            help="For Azure AD/Entra, enter the tenant GUID. Only users from this tenant can authenticate for this domain."
          >
            <template #default="{ id: fieldId }">
              <InputText
                :id="fieldId"
                v-model="editForm.requiredOidcTenantId"
                placeholder="e.g., 2e789bd9-a313-462a-b520-df9b586c00ed"
                :invalid="isOidcMultiTenant && !editForm.requiredOidcTenantId.trim()"
              />
            </template>
          </FcFormField>

          <Message
            v-if="editForm.scopeType === 'ANCHOR'"
            severity="info"
            :closable="false"
            class="fc-span-2"
          >
            Anchor users have platform admin access and can access all clients.
          </Message>
          <Message
            v-if="editForm.scopeType === 'PARTNER'"
            severity="info"
            :closable="false"
            class="fc-span-2"
          >
            Partner users can be granted access to multiple clients after login.
          </Message>
        </div>
      </FcFormSection>

      <!-- Role Mapping -->
      <FcFormSection
        v-if="isEditing ? showRolePicker : showRoleDisplay"
        title="Role Mapping"
        flat
      >
        <!-- View mode -->
        <div v-if="!isEditing" class="fc-detail-grid">
          <FcDetailField label="Allowed Roles" span>
            <div v-if="(mapping.allowedRoleIds?.length ?? 0) > 0" class="role-chips">
              <Chip v-for="roleName in getAllowedRoleNames()" :key="roleName" :label="roleName" />
            </div>
            <span v-else class="muted">All roles allowed</span>
          </FcDetailField>
          <FcDetailField label="Sync Roles from IDP">
            <Tag
              :value="mapping.syncRolesFromIdp ? 'Enabled' : 'Disabled'"
              :severity="mapping.syncRolesFromIdp ? 'success' : 'secondary'"
            />
          </FcDetailField>
        </div>

        <!-- Edit mode -->
        <div v-else class="fc-form-grid">
          <FcFormField
            label="Allowed Roles"
            span
            help="Restrict which roles users from this domain can be assigned. Move roles to the right to allow them. Leave empty to allow all roles."
          >
            <PickList
              v-model="rolePickerModel"
              dataKey="id"
              breakpoint="960px"
              :showSourceControls="false"
              :showTargetControls="false"
            >
              <template #sourceheader>Available Roles</template>
              <template #targetheader>Allowed Roles</template>
              <template #item="{ item }">
                <div class="role-item">
                  <span class="role-name">{{ item.displayName || item.name }}</span>
                  <span class="role-app">{{ item.applicationCode }}</span>
                </div>
              </template>
            </PickList>
          </FcFormField>

          <FcFormField
            label="Sync Roles from IDP"
            span
            help="When enabled, roles from the external IDP token will be synchronized during OIDC login. Synced roles are filtered by the allowed roles list above."
          >
            <template #default="{ id: fieldId }">
              <div class="toggle-row">
                <ToggleSwitch :inputId="fieldId" v-model="editForm.syncRolesFromIdp" />
                <span class="toggle-label">{{
                  editForm.syncRolesFromIdp ? 'Enabled' : 'Disabled'
                }}</span>
              </div>
            </template>
          </FcFormField>
        </div>
      </FcFormSection>

      <!-- Two-Factor Authentication -->
      <FcFormSection v-if="show2faControls" title="Two-Factor Authentication" flat>
        <!-- View mode -->
        <div v-if="!isEditing" class="fc-detail-grid">
          <FcDetailField label="Two-Factor Authentication">
            <Tag
              :value="mapping.require2fa ? 'Required' : 'Optional'"
              :severity="mapping.require2fa ? 'success' : 'secondary'"
            />
          </FcDetailField>
          <FcDetailField v-if="mapping.require2fa" label="Allowed 2FA Methods">
            <div class="role-chips">
              <Chip
                v-for="m in mapping.allowed2faMethods"
                :key="m"
                :label="m === 'TOTP' ? 'Authenticator app' : 'Email code'"
              />
            </div>
          </FcDetailField>
          <FcDetailField
            v-if="mapping.require2fa"
            label="Remember Device"
            :value="mapping.rememberDeviceEnabled ? `Allowed (${mapping.rememberDeviceDays} days)` : 'Off'"
          />
        </div>

        <!-- Edit mode -->
        <div v-else class="fc-form-grid">
          <FcFormField
            label="Require Two-Factor Authentication"
            help="Applies to password sign-in for users of this domain. Passkey sign-in is unaffected; federated (SSO) users are never prompted."
          >
            <template #default="{ id: fieldId }">
              <div class="toggle-row">
                <ToggleSwitch :inputId="fieldId" v-model="editForm.require2fa" />
                <span class="toggle-label">{{ editForm.require2fa ? 'Required' : 'Optional' }}</span>
              </div>
            </template>
          </FcFormField>

          <FcFormField v-if="editForm.require2fa" label="Allowed 2FA Methods">
            <div class="toggle-row checkbox-group">
              <label class="checkbox-row">
                <Checkbox
                  :modelValue="editForm.allowed2faMethods.includes('TOTP')"
                  binary
                  @update:modelValue="(on: boolean) => toggle2faMethod('TOTP', on)"
                />
                Authenticator app
              </label>
              <label class="checkbox-row">
                <Checkbox
                  :modelValue="editForm.allowed2faMethods.includes('EMAIL_PIN')"
                  binary
                  @update:modelValue="(on: boolean) => toggle2faMethod('EMAIL_PIN', on)"
                />
                Email code
              </label>
            </div>
          </FcFormField>

          <Message
            v-if="editForm.require2fa && editForm.allowed2faMethods.length === 0"
            severity="warn"
            :closable="false"
            class="fc-span-2"
          >
            Select at least one method.
          </Message>

          <FcFormField v-if="editForm.require2fa" label="Allow &quot;remember this device&quot;">
            <template #default="{ id: fieldId }">
              <div class="toggle-row">
                <ToggleSwitch :inputId="fieldId" v-model="editForm.rememberDeviceEnabled" />
                <span class="toggle-label">{{ editForm.rememberDeviceEnabled ? 'Allowed' : 'Off' }}</span>
              </div>
            </template>
          </FcFormField>

          <FcFormField
            v-if="editForm.require2fa && editForm.rememberDeviceEnabled"
            label="Remember for (days)"
          >
            <template #default="{ id: fieldId }">
              <InputNumber
                :inputId="fieldId"
                v-model="editForm.rememberDeviceDays"
                :min="1"
                :max="365"
                showButtons
              />
            </template>
          </FcFormField>
        </div>
      </FcFormSection>
    </template>

    <template v-if="mapping && !loading && !loadError" #footer>
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
        <Button label="Cancel" text :disabled="saving" @click="cancelEditing" />
        <Button
          label="Save Changes"
          icon="pi pi-check"
          :loading="saving"
          :disabled="!isValid"
          @click="saveChanges"
        />
      </FcFormActions>
    </template>
  </EntityDrawer>

  <!-- Delete Confirmation Dialog -->
  <Dialog
    v-model:visible="showDeleteDialog"
    header="Delete Email Domain Mapping"
    modal
    :style="{ width: '450px' }"
  >
    <div class="dialog-content">
      <p>
        Are you sure you want to delete the mapping for
        <strong>{{ mapping?.emailDomain }}</strong
        >?
      </p>

      <Message severity="warn" :closable="false">
        Users from this domain will no longer be able to authenticate.
      </Message>
    </div>

    <template #footer>
      <Button label="Cancel" text :disabled="deleteLoading" @click="showDeleteDialog = false" />
      <Button
        label="Delete"
        icon="pi pi-trash"
        severity="danger"
        :loading="deleteLoading"
        @click="deleteMapping"
      />
    </template>
  </Dialog>
</template>

<style scoped>
.save-error {
  margin-bottom: 16px;
}

.domain-value {
  font-family: monospace;
  background: #f1f5f9;
  padding: 4px 8px;
  border-radius: 4px;
  display: inline-block;
}

.tenant-id {
  font-family: monospace;
  background: #f1f5f9;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
}

.muted {
  color: #94a3b8;
  font-style: italic;
}

.type-option {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 0;
}

.type-option .type-label {
  font-size: 14px;
  font-weight: 500;
}

.type-option .type-description {
  font-size: 12px;
  color: #64748b;
}

.client-select {
  display: flex;
  gap: 8px;
  align-items: center;
}

.client-select .p-autocomplete {
  flex: 1;
}

.dialog-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.role-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.role-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 0;
}

.role-item .role-name {
  font-size: 14px;
  font-weight: 500;
}

.role-item .role-app {
  font-size: 12px;
  color: #64748b;
  font-family: monospace;
}

.toggle-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.checkbox-group {
  gap: 16px;
}

.checkbox-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  color: #475569;
  cursor: pointer;
}

.toggle-label {
  font-size: 14px;
  color: #475569;
}

:deep(.p-picklist) {
  max-width: 100%;
}

:deep(.p-picklist-list) {
  min-height: 160px;
  max-height: 240px;
}
</style>
