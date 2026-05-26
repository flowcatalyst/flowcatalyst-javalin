<script setup lang="ts">
import { ref, watch, onMounted } from "vue";
import { clientsApi, type Client } from "@/api/clients";

interface Props {
	modelValue: string | null;
	placeholder?: string;
	disabled?: boolean;
	showClear?: boolean;
	status?: string;
	invalid?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
	placeholder: "Search for a client...",
	disabled: false,
	showClear: true,
	status: "ACTIVE",
	invalid: false,
});

const emit = defineEmits<{
	"update:modelValue": [value: string | null];
	"client-selected": [client: Client | null];
}>();

// Internal state
const selectedClient = ref<Client | null>(null);
const suggestions = ref<Client[]>([]);
const loading = ref(false);

// Load initial client if modelValue is provided
onMounted(async () => {
	if (props.modelValue) {
		await loadClientById(props.modelValue);
	}
});

// Watch for external modelValue changes
watch(
	() => props.modelValue,
	async (newValue) => {
		if (
			newValue &&
			(!selectedClient.value || selectedClient.value.id !== newValue)
		) {
			await loadClientById(newValue);
		} else if (!newValue) {
			selectedClient.value = null;
		}
	},
);

async function loadClientById(id: string) {
	try {
		const client = await clientsApi.get(id);
		selectedClient.value = client;
	} catch (e) {
		console.error("Failed to load client:", e);
		selectedClient.value = null;
	}
}

async function searchClients(event: { query: string }) {
	loading.value = true;
	try {
		const response = await clientsApi.search({
			q: event.query,
			status: props.status,
			limit: 20,
		});
		suggestions.value = response.clients;
	} catch (e) {
		console.error("Failed to search clients:", e);
		suggestions.value = [];
	} finally {
		loading.value = false;
	}
}

function onSelect(event: { value: Client }) {
	selectedClient.value = event.value;
	emit("update:modelValue", event.value.id);
	emit("client-selected", event.value);
}

function onClear() {
	selectedClient.value = null;
	emit("update:modelValue", null);
	emit("client-selected", null);
}
</script>

<template>
  <AutoComplete
    v-model="selectedClient"
    :suggestions="suggestions"
    optionLabel="name"
    :placeholder="placeholder"
    :disabled="disabled"
    :loading="loading"
    :invalid="invalid"
    :dropdown="true"
    dropdownMode="current"
    forceSelection
    class="client-select"
    @complete="searchClients"
    @item-select="onSelect"
    @clear="onClear"
  >
    <template #option="{ option }">
      <div class="client-option">
        <span class="client-name">{{ option.name }}</span>
        <span class="client-identifier">{{ option.identifier }}</span>
      </div>
    </template>
    <template #empty>
      <div class="empty-message">No clients found</div>
    </template>
  </AutoComplete>
</template>

<style scoped>
.client-select {
  width: 100%;
}

.client-select :deep(.p-autocomplete-input) {
  width: 100%;
}

.client-option {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 0;
}

.client-name {
  font-weight: 500;
}

.client-identifier {
  font-size: 12px;
  color: #64748b;
  font-family: monospace;
}

.empty-message {
  padding: 8px 12px;
  color: #64748b;
  font-size: 13px;
}
</style>
