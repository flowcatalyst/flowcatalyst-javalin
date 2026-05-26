<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import {
	identityProvidersApi,
	type IdentityProvider,
} from "@/api/identity-providers";
import { useListState } from "@/composables/useListState";
import { useReturnTo } from "@/composables/useReturnTo";

const router = useRouter();
const { navigateToDetail } = useReturnTo();
const providers = ref<IdentityProvider[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);

const { filters } = useListState({
	filters: {
		q: { type: "string", key: "q" },
	},
});

// Delete dialog state
const showDeleteDialog = ref(false);
const providerToDelete = ref<IdentityProvider | null>(null);
const deleteLoading = ref(false);

const filteredProviders = computed(() => {
	if (!filters.q.value) return providers.value;
	const query = filters.q.value.toLowerCase();
	return providers.value.filter(
		(provider) =>
			provider.name.toLowerCase().includes(query) ||
			provider.code.toLowerCase().includes(query) ||
			provider.type.toLowerCase().includes(query),
	);
});

onMounted(async () => {
	await loadProviders();
});

async function loadProviders() {
	loading.value = true;
	error.value = null;
	try {
		const response = await identityProvidersApi.list();
		providers.value = response.identityProviders;
	} catch (e) {
		error.value =
			e instanceof Error ? e.message : "Failed to load identity providers";
	} finally {
		loading.value = false;
	}
}

function confirmDelete(provider: IdentityProvider) {
	providerToDelete.value = provider;
	showDeleteDialog.value = true;
}

async function deleteProvider() {
	if (!providerToDelete.value) return;

	deleteLoading.value = true;

	try {
		await identityProvidersApi.delete(providerToDelete.value.id);
		providers.value = providers.value.filter(
			(p) => p.id !== providerToDelete.value?.id,
		);
		showDeleteDialog.value = false;
		toast.success("Success", `Identity provider "${providerToDelete.value.name}" deleted`);
	} catch (e: unknown) {
	} finally {
		deleteLoading.value = false;
		providerToDelete.value = null;
	}
}

function getTypeSeverity(type: string) {
	return type === "OIDC" ? "info" : "secondary";
}

function formatDate(dateString: string) {
	return new Date(dateString).toLocaleDateString();
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Identity Providers</h1>
        <p class="page-subtitle">
          Configure external identity providers for federated authentication.
        </p>
      </div>
      <Button
        label="Add Identity Provider"
        icon="pi pi-plus"
        @click="router.push('/authentication/identity-providers/new')"
      />
    </header>

    <Message v-if="error" severity="error" class="error-message">{{ error }}</Message>

    <div class="fc-card">
      <div class="toolbar">
        <IconField class="search-wrapper">
          <InputIcon class="pi pi-search" />
          <InputText v-model="filters.q.value" placeholder="Search providers..." />
        </IconField>
      </div>

      <div v-if="loading" class="loading-container">
        <ProgressSpinner strokeWidth="3" />
      </div>

      <DataTable
        v-else
        :value="filteredProviders"
        paginator
        :rows="100"
        :rowsPerPageOptions="[50, 100, 250, 500]"
        stripedRows
        emptyMessage="No identity providers found"
      >
        <Column field="name" header="Name" sortable>
          <template #body="{ data }">
            <span class="provider-name">{{ data.name }}</span>
          </template>
        </Column>
        <Column field="code" header="Code" sortable>
          <template #body="{ data }">
            <code class="code-value">{{ data.code }}</code>
          </template>
        </Column>
        <Column field="type" header="Type" sortable>
          <template #body="{ data }">
            <Tag :value="data.type" :severity="getTypeSeverity(data.type)" />
          </template>
        </Column>
        <Column header="Issuer">
          <template #body="{ data }">
            <span v-if="data.oidcIssuerUrl" class="issuer-url">{{ data.oidcIssuerUrl }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </Column>
        <Column header="Allowed Domains">
          <template #body="{ data }">
            <div v-if="data.allowedEmailDomains?.length > 0" class="domain-tags">
              <Tag
                v-for="domain in data.allowedEmailDomains.slice(0, 3)"
                :key="domain"
                :value="domain"
                severity="secondary"
                class="domain-tag"
              />
              <span v-if="data.allowedEmailDomains.length > 3" class="more-domains">
                +{{ data.allowedEmailDomains.length - 3 }} more
              </span>
            </div>
            <span v-else class="text-muted">All domains</span>
          </template>
        </Column>
        <Column field="createdAt" header="Created" sortable>
          <template #body="{ data }">
            {{ formatDate(data.createdAt) }}
          </template>
        </Column>
        <Column header="Actions" style="width: 100px">
          <template #body="{ data }">
            <div class="action-buttons">
              <Button
                icon="pi pi-eye"
                text
                rounded
                v-tooltip="'View Details'"
                @click="navigateToDetail(`/authentication/identity-providers/${data.id}`)"
              />
              <Button
                icon="pi pi-trash"
                text
                rounded
                severity="danger"
                v-tooltip="'Delete'"
                @click="confirmDelete(data)"
              />
            </div>
          </template>
        </Column>
      </DataTable>
    </div>

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
          <strong>{{ providerToDelete?.name }}</strong
          >?
        </p>

        <Message severity="warn" :closable="false" class="warning-message">
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
.toolbar {
  margin-bottom: 16px;
}

.search-wrapper :deep(.pi-search) {
  color: #94a3b8;
}

.loading-container {
  display: flex;
  justify-content: center;
  padding: 60px;
}

.error-message {
  margin-bottom: 16px;
}

.provider-name {
  font-weight: 500;
  color: #1e293b;
}

.code-value {
  background: #f1f5f9;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-family: monospace;
}

.issuer-url {
  font-size: 12px;
  color: #64748b;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
}

.domain-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

.domain-tag {
  font-size: 11px;
}

.more-domains {
  font-size: 12px;
  color: #64748b;
}

.text-muted {
  color: #94a3b8;
}

.dialog-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.warning-message {
  margin: 0;
}

.action-buttons {
  display: flex;
  flex-wrap: nowrap;
  gap: 0;
}
</style>
