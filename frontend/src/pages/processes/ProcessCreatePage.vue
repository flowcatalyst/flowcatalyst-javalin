<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { toast } from "@/utils/errorBus";
import { processesApi } from "@/api/processes";

const router = useRouter();

const code = ref("");
const name = ref("");
const description = ref("");
const body = ref("");
const tagsText = ref("");
const saving = ref(false);

const placeholderBody = `graph TD
  A[Fulfilment Created] --> B{Geocoded?}
  B -- No --> C[Enrich locations via dispatch job]
  C --> D[Reactive Aggregate: build shipment]
  B -- Yes --> D
  D --> E[Shipment Created]`;

async function save() {
	if (!code.value.trim() || !name.value.trim()) {
		toast.warn("Validation", "Code and name are required.");
		return;
	}
	saving.value = true;
	try {
		const tags = tagsText.value
			.split(",")
			.map((t) => t.trim())
			.filter(Boolean);
		const result = await processesApi.create({
			code: code.value.trim(),
			name: name.value.trim(),
			description: description.value.trim() || undefined,
			body: body.value,
			tags,
		});
		toast.success("Process created", code.value);
		router.push(`/processes/${result.id}`);
	} catch (e) {
		toast.error("Failed to create", (e as Error).message);
	} finally {
		saving.value = false;
	}
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <Button
          label="Back to processes"
          icon="pi pi-arrow-left"
          text
          severity="secondary"
          @click="router.push('/processes')"
        />
        <h1 class="page-title">New process</h1>
        <p class="page-subtitle">
          Document a workflow with a Mermaid diagram. The platform stores the source verbatim
          and renders it client-side.
        </p>
      </div>
    </header>

    <div class="fc-card">
      <div class="form-grid">
        <div class="form-field">
          <label>Code</label>
          <InputText
            v-model="code"
            placeholder="orders:fulfilment:shipment-flow"
            class="full-width"
          />
          <small>Format: <code>application:subdomain:process-name</code></small>
        </div>

        <div class="form-field">
          <label>Name</label>
          <InputText v-model="name" placeholder="Shipment Flow" class="full-width" />
        </div>

        <div class="form-field full-row">
          <label>Description</label>
          <Textarea
            v-model="description"
            placeholder="One-line summary of what this process documents"
            :autoResize="true"
            rows="2"
            class="full-width"
          />
        </div>

        <div class="form-field full-row">
          <label>Tags</label>
          <InputText
            v-model="tagsText"
            placeholder="fulfilment, billing"
            class="full-width"
          />
          <small>Comma-separated.</small>
        </div>

        <div class="form-field full-row">
          <label>Mermaid source</label>
          <Textarea
            v-model="body"
            :placeholder="placeholderBody"
            rows="14"
            class="full-width source-input"
          />
          <small>
            Anything <a href="https://mermaid.js.org/syntax/flowchart.html" target="_blank" rel="noopener">Mermaid</a>
            supports — flowcharts, sequence diagrams, state machines.
          </small>
        </div>
      </div>

      <div class="form-actions">
        <Button
          label="Cancel"
          severity="secondary"
          text
          @click="router.push('/processes')"
        />
        <Button label="Create" icon="pi pi-check" :loading="saving" @click="save" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  padding: 8px 0;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-field.full-row {
  grid-column: 1 / -1;
}

.form-field label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-color-secondary);
}

.form-field small {
  color: var(--text-color-secondary);
  font-size: 12px;
}

.full-width {
  width: 100%;
}

.source-input :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
  border-top: 1px solid var(--surface-border);
  padding-top: 16px;
}
</style>
