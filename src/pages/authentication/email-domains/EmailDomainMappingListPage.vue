<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import {
	emailDomainMappingsApi,
	type EmailDomainMapping,
} from "@/api/email-domain-mappings";
import { useListState } from "@/composables/useListState";
import { useReturnTo } from "@/composables/useReturnTo";

const router = useRouter();
const { navigateToDetail } = useReturnTo();
const mappings = ref<EmailDomainMapping[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);

const { filters } = useListState({
	filters: {
		q: { type: "string", key: "q" },
	},
});

// Delete dialog state
const showDeleteDialog = ref(false);
const mappingToDelete = ref<EmailDomainMapping | null>(null);
const deleteLoading = ref(false);

const filteredMappings = computed(() => {
	if (!filters.q.value) return mappings.value;
	const query = filters.q.value.toLowerCase();
	return mappings.value.filter(
		(mapping) =>
			mapping.emailDomain.toLowerCase().includes(query) ||
			mapping.scopeType.toLowerCase().includes(query) ||
			(mapping.identityProviderName || "").toLowerCase().includes(query),
	);
});

onMounted(async () => {
	await loadMappings();
});

async function loadMappings() {
	loading.value = true;
	error.value = null;
	try {
		const response = await emailDomainMappingsApi.list();
		mappings.value = response.mappings;
	} catch (e) {
		error.value =
			e instanceof Error ? e.message : "Failed to load email domain mappings";
	} finally {
		loading.value = false;
	}
}

function confirmDelete(mapping: EmailDomainMapping) {
	mappingToDelete.value = mapping;
	showDeleteDialog.value = true;
}

async function deleteMapping() {
	if (!mappingToDelete.value) return;

	deleteLoading.value = true;

	try {
		await emailDomainMappingsApi.delete(mappingToDelete.value.id);
		mappings.value = mappings.value.filter(
			(m) => m.id !== mappingToDelete.value?.id,
		);
		showDeleteDialog.value = false;
		toast.success("Success", `Email domain mapping for "${mappingToDelete.value.emailDomain}" deleted`);
	} catch (e: unknown) {
	} finally {
		deleteLoading.value = false;
		mappingToDelete.value = null;
	}
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

function formatDate(dateString: string) {
	return new Date(dateString).toLocaleDateString();
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Email Domain Mappings</h1>
        <p class="page-subtitle">Map email domains to identity providers and define user scope.</p>
      </div>
      <Button
        label="Add Domain Mapping"
        icon="pi pi-plus"
        @click="router.push('/authentication/email-domain-mappings/new')"
      />
    </header>

    <Message v-if="error" severity="error" class="error-message">{{ error }}</Message>

    <div class="fc-card">
      <div class="toolbar">
        <IconField class="search-wrapper">
          <InputIcon class="pi pi-search" />
          <InputText v-model="filters.q.value" placeholder="Search domains..." />
        </IconField>
      </div>

      <div v-if="loading" class="loading-container">
        <ProgressSpinner strokeWidth="3" />
      </div>

      <DataTable
        v-else
        :value="filteredMappings"
        paginator
        :rows="100"
        :rowsPerPageOptions="[50, 100, 250, 500]"
        stripedRows
        emptyMessage="No email domain mappings found"
      >
        <Column field="emailDomain" header="Email Domain" sortable>
          <template #body="{ data }">
            <span class="domain-name">{{ data.emailDomain }}</span>
          </template>
        </Column>
        <Column header="Identity Provider" sortable>
          <template #body="{ data }">
            <span class="provider-name">{{ data.identityProviderName || 'Unknown' }}</span>
            <Tag
              v-if="data.identityProviderType"
              :value="data.identityProviderType"
              :severity="data.identityProviderType === 'OIDC' ? 'info' : 'secondary'"
              class="provider-type-tag"
            />
          </template>
        </Column>
        <Column field="scopeType" header="Scope Type" sortable>
          <template #body="{ data }">
            <Tag :value="data.scopeType" :severity="getScopeTypeSeverity(data.scopeType)" />
          </template>
        </Column>
        <Column header="Primary Client">
          <template #body="{ data }">
            <span v-if="data.primaryClientName" class="client-name">{{
              data.primaryClientName
            }}</span>
            <span v-else class="text-muted">-</span>
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
                @click="navigateToDetail(`/authentication/email-domain-mappings/${data.id}`)"
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
      header="Delete Email Domain Mapping"
      modal
      :style="{ width: '450px' }"
    >
      <div class="dialog-content">
        <p>
          Are you sure you want to delete the mapping for
          <strong>{{ mappingToDelete?.emailDomain }}</strong
          >?
        </p>

        <Message severity="warn" :closable="false" class="warning-message">
          Users from this domain will no longer be able to authenticate.
        </Message>
      </div>

      <template #footer>
        <Button label="Cancel" text @click="showDeleteDialog = false" :disabled="deleteLoading" />
        <Button
          label="Delete"
          icon="pi pi-trash"
          severity="danger"
          @click="deleteMapping"
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


.loading-container {
  display: flex;
  justify-content: center;
  padding: 60px;
}

.error-message {
  margin-bottom: 16px;
}

.domain-name {
  font-weight: 500;
  color: #1e293b;
}

.provider-name {
  color: #1e293b;
}

.provider-type-tag {
  margin-left: 8px;
}

.client-name {
  color: #1e293b;
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
