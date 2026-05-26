<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, computed } from "vue";
import { useRouter } from "vue-router";
import {
	identityProvidersApi,
	type CreateIdentityProviderRequest,
	type IdentityProviderType,
} from "@/api/identity-providers";
import { getErrorMessage } from "@/utils/errors";

const router = useRouter();

const loading = ref(false);
const error = ref<string | null>(null);

// Form state
const form = ref({
	code: "",
	name: "",
	type: "OIDC" as IdentityProviderType,
	oidcIssuerUrl: "",
	oidcClientId: "",
	oidcClientSecretRef: "",
	oidcMultiTenant: false,
	oidcIssuerPattern: "",
	allowedEmailDomains: [] as string[],
});

const newAllowedDomain = ref("");

const typeOptions = [
	{
		label: "Internal (Local)",
		value: "INTERNAL",
		description: "Internal authentication (username/password)",
	},
	{
		label: "OIDC (External)",
		value: "OIDC",
		description: "External OpenID Connect provider",
	},
];

const CODE_PATTERN = /^[a-z][a-z0-9-]*$/;

const isCodeValid = computed(() => {
	return !form.value.code || CODE_PATTERN.test(form.value.code);
});

const isValid = computed(() => {
	if (!form.value.code.trim() || !form.value.name.trim()) return false;
	if (!isCodeValid.value) return false;
	if (form.value.type === "OIDC") {
		if (!form.value.oidcClientId.trim()) return false;
		if (!form.value.oidcIssuerUrl.trim()) return false; // Always required for OIDC
	}
	return true;
});

function addAllowedDomain() {
	const domain = newAllowedDomain.value.trim().toLowerCase();
	if (domain && !form.value.allowedEmailDomains.includes(domain)) {
		if (domain.match(/^[a-z0-9][a-z0-9.-]*\.[a-z]{2,}$/)) {
			form.value.allowedEmailDomains.push(domain);
			newAllowedDomain.value = "";
		} else {
			toast.error("Invalid Domain", "Please enter a valid domain name");
		}
	}
}

function removeAllowedDomain(domain: string) {
	form.value.allowedEmailDomains = form.value.allowedEmailDomains.filter(
		(d) => d !== domain,
	);
}

async function createProvider() {
	if (!isValid.value) return;

	loading.value = true;
	error.value = null;

	try {
		const requestData: CreateIdentityProviderRequest = {
			code: form.value.code.trim(),
			name: form.value.name.trim(),
			type: form.value.type,
			allowedEmailDomains:
				form.value.allowedEmailDomains.length > 0
					? form.value.allowedEmailDomains
					: undefined,
			...(form.value.type === "OIDC"
				? {
						oidcIssuerUrl:
							form.value.oidcIssuerUrl.trim() || undefined,
						oidcClientId: form.value.oidcClientId.trim(),
						oidcClientSecretRef:
							form.value.oidcClientSecretRef.trim() || undefined,
						oidcMultiTenant: form.value.oidcMultiTenant,
						oidcIssuerPattern:
							form.value.oidcIssuerPattern.trim() || undefined,
					}
				: {}),
		};

		const created = await identityProvidersApi.create(requestData);
		toast.success("Success", `Identity provider "${created.name}" created successfully`);
		router.push(`/authentication/identity-providers/${created.id}`);
	} catch (e: unknown) {
		error.value = getErrorMessage(e, "Failed to create identity provider");
	} finally {
		loading.value = false;
	}
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
          @click="router.push('/authentication/identity-providers')"
        />
        <h1 class="page-title">Create Identity Provider</h1>
        <p class="page-subtitle">Configure a new identity provider for federated authentication.</p>
      </div>
    </header>

    <Message
      v-if="error"
      severity="error"
      class="error-message"
      :closable="true"
      @close="error = null"
    >
      {{ error }}
    </Message>

    <div class="fc-card">
      <div class="form-content">
        <div class="field">
          <label for="code">Code *</label>
          <InputText
            id="code"
            v-model="form.code"
            placeholder="e.g., google, azure-ad, okta"
            class="w-full"
            :invalid="!!(form.code && !isCodeValid)"
          />
          <small v-if="form.code && !isCodeValid" class="p-error">
            Lowercase letters, numbers, and hyphens only. Must start with a letter.
          </small>
          <small v-else class="field-help"
            >A unique identifier for this provider (cannot be changed later)</small
          >
        </div>

        <div class="field">
          <label for="name">Name *</label>
          <InputText
            id="name"
            v-model="form.name"
            placeholder="e.g., Google Workspace, Azure AD"
            class="w-full"
          />
          <small class="field-help">A human-readable name for this provider</small>
        </div>

        <div class="field">
          <label for="type">Type *</label>
          <Select
            id="type"
            v-model="form.type"
            :options="typeOptions"
            optionLabel="label"
            optionValue="value"
            class="w-full"
          >
            <template #option="slotProps">
              <div class="type-option">
                <span class="type-label">{{ slotProps.option.label }}</span>
                <span class="type-description">{{ slotProps.option.description }}</span>
              </div>
            </template>
          </Select>
        </div>

        <template v-if="form.type === 'OIDC'">
          <div class="field checkbox-field">
            <Checkbox id="multiTenant" v-model="form.oidcMultiTenant" :binary="true" />
            <label for="multiTenant" class="checkbox-label">Multi-Tenant Mode</label>
          </div>
          <small class="field-help">
            Enable for providers like Azure AD where the issuer varies per tenant
          </small>

          <div class="field">
            <label for="issuerUrl">Issuer URL *</label>
            <InputText
              id="issuerUrl"
              v-model="form.oidcIssuerUrl"
              :placeholder="
                form.oidcMultiTenant
                  ? 'https://login.microsoftonline.com/organizations/v2.0'
                  : 'https://login.example.com'
              "
              class="w-full"
            />
            <small class="field-help">
              {{
                form.oidcMultiTenant
                  ? 'Base URL for authorization/token endpoints (e.g., .../organizations/v2.0 or .../common/v2.0)'
                  : 'The OpenID Connect issuer URL'
              }}
            </small>
          </div>

          <div v-if="form.oidcMultiTenant" class="field">
            <label for="issuerPattern">Issuer Pattern</label>
            <InputText
              id="issuerPattern"
              v-model="form.oidcIssuerPattern"
              placeholder="https://login.microsoftonline.com/{tenantId}/v2.0"
              class="w-full"
            />
            <small class="field-help">
              Pattern for validating token issuer. Use {tenantId} as placeholder. Leave empty to
              auto-derive from Issuer URL.
            </small>
          </div>

          <div class="field">
            <label for="clientId">Client ID *</label>
            <InputText
              id="clientId"
              v-model="form.oidcClientId"
              placeholder="Your OAuth client ID"
              class="w-full"
            />
          </div>

          <div class="field">
            <label for="clientSecret">Client Secret</label>
            <InputText
              id="clientSecret"
              v-model="form.oidcClientSecretRef"
              type="password"
              placeholder="Your OAuth client secret"
              class="w-full"
            />
            <small class="field-help">Required for confidential clients</small>
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
          <div v-if="form.allowedEmailDomains.length > 0" class="domain-list">
            <Chip
              v-for="domain in form.allowedEmailDomains"
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
          <Button
            label="Cancel"
            text
            @click="router.push('/authentication/identity-providers')"
            :disabled="loading"
          />
          <Button
            label="Create Identity Provider"
            icon="pi pi-plus"
            @click="createProvider"
            :loading="loading"
            :disabled="!isValid"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.back-button {
  margin-right: 8px;
}

.error-message {
  margin-bottom: 16px;
}

.form-content {
  display: flex;
  flex-direction: column;
  gap: 20px;
  max-width: 600px;
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

.domain-input {
  display: flex;
  gap: 8px;
}

.flex-grow {
  flex: 1;
}

.domain-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid #e2e8f0;
}

.w-full {
  width: 100%;
}
</style>
