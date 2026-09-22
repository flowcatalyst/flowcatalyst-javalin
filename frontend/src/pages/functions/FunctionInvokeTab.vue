<script setup lang="ts">
// Invoke tab (docs/spec/function-ui.md §2.1, `platform:function:version:invoke`
// only). The SPA talks to the platform, never the function host directly —
// this tab makes NO network call. It renders ready-made `fcdev fn invoke`
// and curl lines for the private entry, with a version selector for the
// versioned ("exercise a candidate") form, and copy buttons only.
import { computed, ref } from "vue";
import { toast } from "@/utils/errorBus";
import type { VersionResponse } from "@/api/functions";

const props = defineProps<{
	address: string;
	versions: VersionResponse[];
	liveVersion?: number;
}>();

const path = ref("/healthz");

const versionOptions = computed(() =>
	props.versions.map((v) => ({ label: `v${v.version} (${v.state})`, value: v.version })),
);

const selectedVersion = ref<number | null>(props.liveVersion ?? null);
if (selectedVersion.value === null && props.versions.length > 0) {
	selectedVersion.value = props.versions[0]?.version ?? null;
}

const normalizedPath = computed(() => {
	const p = path.value.trim();
	if (!p) return "/";
	return p.startsWith("/") ? p : `/${p}`;
});

const fcdevLiveCommand = computed(
	() => `fcdev fn invoke ${props.address} --path ${normalizedPath.value}`,
);
const curlLiveCommand = computed(
	() =>
		`curl "$FC_FN_HOST_URL/functions/${props.address}${normalizedPath.value}"`,
);

const fcdevVersionCommand = computed(() => {
	if (selectedVersion.value === null) return "";
	return `fcdev fn invoke ${props.address}:${selectedVersion.value} --path ${normalizedPath.value}`;
});
const curlVersionCommand = computed(() => {
	if (selectedVersion.value === null) return "";
	return (
		`curl -H "Authorization: Bearer $TOKEN" ` +
		`"$FC_FN_HOST_URL/functions/${props.address}:${selectedVersion.value}${normalizedPath.value}"`
	);
});

/** Copy-to-clipboard only — this tab never calls the API. */
function copy(text: string) {
	if (!text) return;
	void navigator.clipboard.writeText(text);
	toast.info("Copied", "Command copied to clipboard");
}
</script>

<template>
  <div class="invoke-tab">
    <Message severity="info" :closable="false" class="invoke-note">
      Developer aid only — nothing here calls the platform. Run these commands yourself; the
      function host is a separate origin the SPA cannot reach reliably.
    </Message>

    <FcFormSection title="Request" flat>
      <div class="fc-form-grid">
        <FcFormField label="Path">
          <template #default="{ id: fieldId }">
            <InputText :id="fieldId" v-model="path" placeholder="/healthz" />
          </template>
        </FcFormField>
      </div>
    </FcFormSection>

    <FcFormSection title="Private entry — live version" flat>
      <div class="command-row">
        <pre data-testid="invoke-live-fcdev">{{ fcdevLiveCommand }}</pre>
        <Button icon="pi pi-copy" text size="small" @click="copy(fcdevLiveCommand)" />
      </div>
      <div class="command-row">
        <pre data-testid="invoke-live-curl">{{ curlLiveCommand }}</pre>
        <Button icon="pi pi-copy" text size="small" @click="copy(curlLiveCommand)" />
      </div>
    </FcFormSection>

    <FcFormSection
      title="Private entry — a specific version"
      description="Needs platform:function:version:invoke and a bearer token; serves the live or newest published version only."
      flat
    >
      <div class="fc-form-grid">
        <FcFormField label="Version">
          <template #default="{ id: fieldId }">
            <Select
              :id="fieldId"
              v-model="selectedVersion"
              :options="versionOptions"
              optionLabel="label"
              optionValue="value"
              appendTo="self"
            />
          </template>
        </FcFormField>
      </div>
      <div class="command-row">
        <pre data-testid="invoke-version-fcdev">{{ fcdevVersionCommand }}</pre>
        <Button icon="pi pi-copy" text size="small" @click="copy(fcdevVersionCommand)" />
      </div>
      <div class="command-row">
        <pre data-testid="invoke-version-curl">{{ curlVersionCommand }}</pre>
        <Button icon="pi pi-copy" text size="small" @click="copy(curlVersionCommand)" />
      </div>
    </FcFormSection>
  </div>
</template>

<style scoped>
.invoke-note {
  margin-bottom: 16px;
}

.command-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.command-row pre {
  flex: 1;
  margin: 0;
  padding: 8px 12px;
  background: #0f172a;
  color: #e2e8f0;
  border-radius: 6px;
  font-size: 12px;
  overflow-x: auto;
}
</style>
