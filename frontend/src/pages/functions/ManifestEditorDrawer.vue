<script setup lang="ts">
// The manifest editor (docs/spec/function-manifest-authoring.md M4,
// docs/spec/function-ui.md §2.6). Opened three ways from the function
// detail: "New manifest" and "Import file" (FunctionVersionsTab's toolbar),
// and "Edit as new version" (a Versions-tab row) — all three just pass a
// different `initialManifest` (or none) into this one drawer.
//
// Two synced views of one model (`model`, a plain PublishManifestRequest —
// no JSON-schema form library, the server is the only validator, spec's
// "no new dependency" rule): a form covering every schema field, and a raw
// JSON textarea. Editing the form re-serialises the JSON view; editing JSON
// re-parses into the form UNLESS it fails to parse, in which case the form
// is left showing the last-good model (disabled) until the text is fixed
// again — never applying a half-typed, invalid document to the form.
import { computed, ref, watch } from "vue";
import { toast } from "@/utils/errorBus";
import { ApiError } from "@/api/client";
import {
	functionsApi,
	type CheckManifestResponse,
	type Manifest,
	type PublishResponse,
} from "@/api/functions";
import {
	cloneManifestModel,
	exportManifest,
	manifestToPrettyJson,
	newManifestModel,
	parseManifestText,
	type ManifestModel,
} from "./manifestModel";
import { renderPlanLines } from "./manifestPlanText";
import PublishVersionDrawer from "./PublishVersionDrawer.vue";

const props = defineProps<{
	address: string;
	/** Starting point: a stored version's manifest ("Edit as new version"),
	 * an imported file's parsed content ("Import file"), or omitted for
	 * "New manifest" (the same template `fn init --manifest-only` writes). */
	initialManifest?: Manifest | ManifestModel | null;
}>();

const emit = defineEmits<{
	close: [];
	published: [version: PublishResponse];
}>();

const model = ref<ManifestModel>(
	props.initialManifest
		? cloneManifestModel(props.initialManifest as ManifestModel)
		: newManifestModel(),
);

const view = ref<"form" | "json">("form");
const jsonDraft = ref(manifestToPrettyJson(model.value));
const jsonParseError = ref<string | null>(null);

// Form -> JSON: any model mutation (form edit, or a successful JSON parse
// below) re-serialises the JSON view, so the two never drift apart.
watch(
	model,
	() => {
		jsonDraft.value = manifestToPrettyJson(model.value);
	},
	{ deep: true },
);

function onJsonInput(text: string) {
	jsonDraft.value = text;
	try {
		const parsed = parseManifestText(text);
		jsonParseError.value = null;
		model.value = parsed;
	} catch (e) {
		// Model is left untouched on purpose — an invalid document must never
		// reach the form (or a Validate/Publish/Export call) half-applied.
		jsonParseError.value = e instanceof Error ? e.message : "invalid JSON";
	}
}

const formReadOnly = computed(() => view.value === "json" && jsonParseError.value !== null);

// --- Validate (M2.2/M2.3) ---------------------------------------------

const checkAlias = ref("live");
const validating = ref(false);
const checkResult = ref<CheckManifestResponse | null>(null);
const checkTransportError = ref<string | null>(null);

const planLines = computed(() =>
	checkResult.value?.plan ? renderPlanLines(checkResult.value.plan) : [],
);

// Errors whose code names an endpoint problem get shown under the Endpoints
// section too, next to the field an author would actually go fix, not just
// buried in the flat list (spec: "attached to the field when the code maps
// to one").
const endpointErrors = computed(() =>
	(checkResult.value?.errors ?? []).filter((e) => e.code.startsWith("ENDPOINT_")),
);

async function validate() {
	validating.value = true;
	checkTransportError.value = null;
	checkResult.value = null;
	try {
		checkResult.value = await functionsApi.checkManifest(props.address, {
			manifest: model.value,
			alias: checkAlias.value.trim() || undefined,
		});
	} catch (e) {
		checkTransportError.value =
			e instanceof ApiError ? e.message : e instanceof Error ? e.message : "Validate failed";
	} finally {
		validating.value = false;
	}
}

// --- Endpoints -----------------------------------------------------------

function addEndpoint() {
	(model.value.endpoints ??= []).push({ path: "", auth: "platform" });
}
function removeEndpoint(i: number) {
	model.value.endpoints?.splice(i, 1);
}
function toggleCors(ep: NonNullable<ManifestModel["endpoints"]>[number]) {
	if (ep.cors) delete ep.cors;
	else ep.cors = { origins: [], methods: [], headers: [], allowCredentials: false };
}

// --- Subscriptions ---------------------------------------------------------

function addSubscription() {
	(model.value.subscriptions ??= []).push({ eventType: "", path: "" });
}
function removeSubscription(i: number) {
	model.value.subscriptions?.splice(i, 1);
}

// --- Schedules -------------------------------------------------------------

function addSchedule() {
	(model.value.schedules ??= []).push({ cron: "", path: "" });
}
function removeSchedule(i: number) {
	model.value.schedules?.splice(i, 1);
}
function schedulePayloadText(s: NonNullable<ManifestModel["schedules"]>[number]): string {
	return s.payload === undefined ? "" : JSON.stringify(s.payload);
}
const schedulePayloadErrors = ref<Record<number, string | undefined>>({});
function setSchedulePayload(
	s: NonNullable<ManifestModel["schedules"]>[number],
	index: number,
	text: string | undefined,
) {
	if (!text || !text.trim()) {
		delete s.payload;
		schedulePayloadErrors.value[index] = undefined;
		return;
	}
	try {
		s.payload = JSON.parse(text);
		schedulePayloadErrors.value[index] = undefined;
	} catch {
		schedulePayloadErrors.value[index] = "payload is not valid JSON";
	}
}

// --- Public routes -----------------------------------------------------------

function addPublicRoute() {
	(model.value.public ??= []).push({ hostname: "" });
}
function removePublicRoute(i: number) {
	model.value.public?.splice(i, 1);
}

// --- Database connections ---------------------------------------------------

function addDbRef() {
	(model.value.db ??= []).push({ name: "", secretRef: "" });
}
function removeDbRef(i: number) {
	model.value.db?.splice(i, 1);
}

// --- comma-list helpers (config/secrets/httpAllow/cors sub-lists/aliasPrefixes) --

function joinList(arr?: string[]): string {
	return (arr ?? []).join(", ");
}
function parseList(text: string | undefined): string[] | undefined {
	const items = (text ?? "")
		.split(",")
		.map((s) => s.trim())
		.filter(Boolean);
	return items.length ? items : undefined;
}

// --- limits (optional sub-object) -------------------------------------------

function addLimitsOverride() {
	model.value.limits = {};
}
function removeLimitsOverride() {
	delete model.value.limits;
}

// --- Export (M4: pretty-printed, $schema included, key order = schema's) ----

function exportFile() {
	const text = JSON.stringify(exportManifest(model.value), null, 2);
	const blob = new Blob([text], { type: "application/json" });
	const url = URL.createObjectURL(blob);
	const a = document.createElement("a");
	a.href = url;
	a.download = "manifest.json";
	a.click();
	URL.revokeObjectURL(url);
}

// --- Import file --------------------------------------------------------

async function onImportFileChange(event: Event) {
	const input = event.target as HTMLInputElement;
	const file = input.files?.[0];
	input.value = "";
	if (!file) return;
	try {
		const text = await file.text();
		model.value = parseManifestText(text);
		toast.success("Imported", `Loaded ${file.name}`);
	} catch {
		toast.error("Import failed", `${file.name} is not valid JSON`);
	}
}

// --- Publish with this manifest ----------------------------------------

const showPublishDrawer = ref(false);
function openPublish() {
	showPublishDrawer.value = true;
}
function onPublished(published: PublishResponse) {
	showPublishDrawer.value = false;
	emit("published", published);
	emit("close");
}

// Each option list is the key set of a Record over the GENERATED union, so the compiler refuses
// a missing or an extra value — the lists cannot silently lag the API document again (the mode
// list once offered two of the three modes the platform accepts).
type Endpoint = NonNullable<ManifestModel["endpoints"]>[number];
type Subscription = NonNullable<ManifestModel["subscriptions"]>[number];
const optionsOf = <T extends string>(all: Record<T, true>): T[] => Object.keys(all) as T[];
const runtimeOptions = optionsOf<ManifestModel["runtime"]>({ jvm: true, wasm: true });
const authOptions = optionsOf<Endpoint["auth"]>({ webhook: true, platform: true, none: true });
const methodOptions = optionsOf<NonNullable<Endpoint["methods"]>[number]>({
	GET: true, HEAD: true, POST: true, PUT: true, PATCH: true, DELETE: true, OPTIONS: true,
});
const modeOptions = optionsOf<NonNullable<Subscription["mode"]>>({
	IMMEDIATE: true, NEXT_ON_ERROR: true, BLOCK_ON_ERROR: true,
});
</script>

<template>
  <Drawer
    :visible="true"
    position="right"
    :modal="false"
    :block-scroll="false"
    :dismissable="false"
    class="entity-drawer entity-drawer-two-thirds manifest-editor-drawer"
    @update:visible="(v: boolean) => !v && emit('close')"
  >
    <template #header>
      <div class="manifest-editor-header">
        <h2 class="manifest-editor-title">Manifest Editor</h2>
        <p class="manifest-editor-subtitle">{{ address }}</p>
      </div>
    </template>

    <div class="editor-toolbar">
      <SelectButton
        v-model="view"
        :options="[
          { label: 'Form', value: 'form' },
          { label: 'JSON', value: 'json' },
        ]"
        option-label="label"
        option-value="value"
        data-testid="manifest-view-toggle"
      />
      <div class="editor-toolbar-actions">
        <label class="import-file-label">
          <input
            type="file"
            accept=".json,application/json"
            data-testid="manifest-import-input"
            @change="onImportFileChange"
          />
        </label>
        <Button
          label="Export"
          icon="pi pi-download"
          text
          size="small"
          data-testid="manifest-export-button"
          @click="exportFile"
        />
      </div>
    </div>

    <Message
      v-if="formReadOnly"
      severity="warn"
      :closable="false"
      data-testid="manifest-json-error"
    >
      manifest.json is not valid JSON — the form reflects the last valid version until this
      is fixed: {{ jsonParseError }}
    </Message>

    <div v-show="view === 'json'" class="json-view">
      <Textarea
        :model-value="jsonDraft"
        rows="24"
        class="manifest-json-textarea"
        data-testid="manifest-json-textarea"
        spellcheck="false"
        @update:model-value="(v: string) => onJsonInput(v)"
      />
    </div>

    <div v-show="view === 'form'" class="form-view" :class="{ 'form-view-disabled': formReadOnly }">
      <fieldset :disabled="formReadOnly" class="form-fieldset">
        <FcFormSection title="Runtime" flat>
          <div class="fc-form-grid">
            <FcFormField label="Runtime" required>
              <Select v-model="model.runtime" :options="runtimeOptions" data-testid="manifest-runtime-select" />
            </FcFormField>
            <FcFormField label="Entrypoint" required>
              <InputText v-model="model.entrypoint" data-testid="manifest-entrypoint-input" />
            </FcFormField>
            <FcFormField label="Pool" help="Defaults to &quot;default&quot; when absent.">
              <InputText v-model="model.pool" data-testid="manifest-pool-input" />
            </FcFormField>
            <FcFormField label="Warm">
              <Checkbox v-model="model.warm" binary data-testid="manifest-warm-checkbox" />
            </FcFormField>
          </div>
        </FcFormSection>

        <FcFormSection title="Limits" flat>
          <template v-if="model.limits">
            <div class="fc-form-grid">
              <FcFormField label="Max duration (ms)">
                <InputNumber
                  :model-value="model.limits.maxDurationMs ?? null"
                  @update:model-value="(v) => (model.limits!.maxDurationMs = v ?? undefined)"
                />
              </FcFormField>
              <FcFormField label="Max concurrency">
                <InputNumber
                  :model-value="model.limits.maxConcurrency ?? null"
                  @update:model-value="(v) => (model.limits!.maxConcurrency = v ?? undefined)"
                />
              </FcFormField>
              <FcFormField v-if="model.runtime === 'wasm'" label="Wasm memory (MB)">
                <InputNumber
                  :model-value="model.limits.wasmMemoryMb ?? null"
                  @update:model-value="(v) => (model.limits!.wasmMemoryMb = v ?? undefined)"
                />
              </FcFormField>
            </div>
            <Button label="Remove limits override" text size="small" @click="removeLimitsOverride" />
          </template>
          <Button v-else label="Add limits override" text size="small" @click="addLimitsOverride" />
        </FcFormSection>

        <FcFormSection title="Endpoints" flat>
          <Message
            v-for="(err, idx) in endpointErrors"
            :key="idx"
            severity="error"
            :closable="false"
            data-testid="endpoints-section-error"
          >
            <strong>{{ err.code }}</strong> {{ err.message }}
          </Message>
          <div
            v-for="(ep, i) in model.endpoints ?? []"
            :key="i"
            class="list-row"
            data-testid="endpoint-row"
          >
            <div class="fc-form-grid">
              <FcFormField label="Path" required>
                <InputText v-model="ep.path" data-testid="endpoint-path-input" />
              </FcFormField>
              <FcFormField label="Auth" required>
                <Select v-model="ep.auth" :options="authOptions" data-testid="endpoint-auth-select" />
              </FcFormField>
              <FcFormField label="Methods" help="Absent/empty means every method.">
                <MultiSelect
                  :model-value="ep.methods ?? []"
                  :options="methodOptions"
                  @update:model-value="(v: string[]) => (ep.methods = v.length ? (v as never) : undefined)"
                />
              </FcFormField>
              <FcFormField label="Max body bytes">
                <InputNumber
                  :model-value="ep.maxBodyBytes ?? null"
                  @update:model-value="(v) => (ep.maxBodyBytes = v ?? undefined)"
                />
              </FcFormField>
              <FcFormField label="Timeout (ms)">
                <InputNumber
                  :model-value="ep.timeoutMs ?? null"
                  @update:model-value="(v) => (ep.timeoutMs = v ?? undefined)"
                />
              </FcFormField>
            </div>
            <div class="cors-subform">
              <label class="cors-toggle">
                <Checkbox :model-value="!!ep.cors" binary @update:model-value="() => toggleCors(ep)" />
                CORS
              </label>
              <div v-if="ep.cors" class="fc-form-grid">
                <FcFormField label="Origins" help="comma-separated; &quot;*&quot; or scheme://host[:port]">
                  <InputText
                    :model-value="joinList(ep.cors.origins)"
                    @update:model-value="(v: string | undefined) => (ep.cors!.origins = parseList(v) ?? [])"
                  />
                </FcFormField>
                <FcFormField label="Methods" help="comma-separated">
                  <InputText
                    :model-value="joinList(ep.cors.methods)"
                    @update:model-value="(v: string | undefined) => (ep.cors!.methods = parseList(v) ?? [])"
                  />
                </FcFormField>
                <FcFormField label="Headers" help="comma-separated">
                  <InputText
                    :model-value="joinList(ep.cors.headers)"
                    @update:model-value="(v: string | undefined) => (ep.cors!.headers = parseList(v) ?? [])"
                  />
                </FcFormField>
                <FcFormField label="Allow credentials">
                  <Checkbox v-model="ep.cors.allowCredentials" binary />
                </FcFormField>
              </div>
            </div>
            <Button
              label="Remove endpoint"
              icon="pi pi-trash"
              text
              size="small"
              severity="danger"
              data-testid="remove-endpoint-button"
              @click="removeEndpoint(i)"
            />
          </div>
          <Button label="Add endpoint" icon="pi pi-plus" text size="small" data-testid="add-endpoint-button" @click="addEndpoint" />
        </FcFormSection>

        <FcFormSection title="Subscriptions" flat>
          <div v-for="(s, i) in model.subscriptions ?? []" :key="i" class="list-row" data-testid="subscription-row">
            <div class="fc-form-grid">
              <FcFormField label="Event type" required>
                <InputText v-model="s.eventType" data-testid="subscription-event-type-input" />
              </FcFormField>
              <FcFormField label="Path" required>
                <InputText v-model="s.path" />
              </FcFormField>
              <FcFormField label="Mode">
                <Select :model-value="s.mode ?? 'IMMEDIATE'" :options="modeOptions" @update:model-value="(v) => (s.mode = v)" />
              </FcFormField>
              <FcFormField label="Max retries">
                <InputNumber
                  :model-value="s.maxRetries ?? null"
                  @update:model-value="(v) => (s.maxRetries = v ?? undefined)"
                />
              </FcFormField>
              <FcFormField label="Timeout (s)">
                <InputNumber
                  :model-value="s.timeoutSeconds ?? null"
                  @update:model-value="(v) => (s.timeoutSeconds = v ?? undefined)"
                />
              </FcFormField>
              <FcFormField label="Data only">
                <Checkbox
                  :model-value="!!s.dataOnly"
                  binary
                  @update:model-value="(v: boolean) => (s.dataOnly = v)"
                />
              </FcFormField>
            </div>
            <Button label="Remove subscription" icon="pi pi-trash" text size="small" severity="danger" @click="removeSubscription(i)" />
          </div>
          <Button label="Add subscription" icon="pi pi-plus" text size="small" data-testid="add-subscription-button" @click="addSubscription" />
        </FcFormSection>

        <FcFormSection title="Schedules" flat>
          <div v-for="(s, i) in model.schedules ?? []" :key="i" class="list-row" data-testid="schedule-row">
            <div class="fc-form-grid">
              <FcFormField label="Cron" required>
                <InputText v-model="s.cron" />
              </FcFormField>
              <FcFormField label="Timezone">
                <InputText v-model="s.timezone" />
              </FcFormField>
              <FcFormField label="Path" required>
                <InputText v-model="s.path" />
              </FcFormField>
              <FcFormField label="Payload (JSON)" span :error="schedulePayloadErrors[i]">
                <Textarea
                  :model-value="schedulePayloadText(s)"
                  rows="2"
                  @update:model-value="(v: string | undefined) => setSchedulePayload(s, i, v)"
                />
              </FcFormField>
            </div>
            <Button label="Remove schedule" icon="pi pi-trash" text size="small" severity="danger" @click="removeSchedule(i)" />
          </div>
          <Button label="Add schedule" icon="pi pi-plus" text size="small" @click="addSchedule" />
        </FcFormSection>

        <FcFormSection title="Public routes" flat>
          <div v-for="(p, i) in model.public ?? []" :key="i" class="list-row" data-testid="public-route-row">
            <div class="fc-form-grid">
              <FcFormField label="Hostname" required>
                <InputText v-model="p.hostname" />
              </FcFormField>
              <FcFormField label="Path prefix" help="Defaults to &quot;/&quot; when absent.">
                <InputText v-model="p.pathPrefix" />
              </FcFormField>
              <FcFormField label="Alias prefixes" help="comma-separated; opt-in, never &quot;live&quot;">
                <InputText
                  :model-value="joinList(p.aliasPrefixes)"
                  @update:model-value="(v: string | undefined) => (p.aliasPrefixes = parseList(v))"
                />
              </FcFormField>
            </div>
            <Button label="Remove route" icon="pi pi-trash" text size="small" severity="danger" @click="removePublicRoute(i)" />
          </div>
          <Button label="Add public route" icon="pi pi-plus" text size="small" @click="addPublicRoute" />
        </FcFormSection>

        <FcFormSection title="Config, secrets & outbound hosts" flat>
          <div class="fc-form-grid">
            <FcFormField label="Config keys" span help="comma-separated required config variable names">
              <InputText
                :model-value="joinList(model.config)"
                data-testid="manifest-config-input"
                @update:model-value="(v: string | undefined) => (model.config = parseList(v))"
              />
            </FcFormField>
            <FcFormField label="Secret keys" span help="comma-separated required secret references">
              <InputText
                :model-value="joinList(model.secrets)"
                @update:model-value="(v: string | undefined) => (model.secrets = parseList(v))"
              />
            </FcFormField>
            <FcFormField label="Outbound hosts (httpAllow)" span help="comma-separated">
              <InputText
                :model-value="joinList(model.httpAllow)"
                @update:model-value="(v: string | undefined) => (model.httpAllow = parseList(v))"
              />
            </FcFormField>
          </div>
        </FcFormSection>

        <FcFormSection title="Database connections" flat>
          <div v-for="(d, i) in model.db ?? []" :key="i" class="list-row" data-testid="db-row">
            <div class="fc-form-grid">
              <FcFormField label="Name" required>
                <InputText v-model="d.name" />
              </FcFormField>
              <FcFormField label="Secret ref" required>
                <InputText v-model="d.secretRef" />
              </FcFormField>
              <FcFormField label="Pool size">
                <InputNumber
                  :model-value="d.poolSize ?? null"
                  @update:model-value="(v) => (d.poolSize = v ?? undefined)"
                />
              </FcFormField>
            </div>
            <Button label="Remove connection" icon="pi pi-trash" text size="small" severity="danger" @click="removeDbRef(i)" />
          </div>
          <Button label="Add connection" icon="pi pi-plus" text size="small" @click="addDbRef" />
        </FcFormSection>
      </fieldset>
    </div>

    <FcFormSection title="Validate" flat>
      <div class="validate-row">
        <FcFormField label="Alias" help="defaults to &quot;live&quot;">
          <InputText v-model="checkAlias" data-testid="manifest-check-alias-input" style="width: 10rem" />
        </FcFormField>
        <Button
          label="Validate"
          icon="pi pi-check-circle"
          :loading="validating"
          data-testid="manifest-validate-button"
          @click="validate"
        />
      </div>

      <Message v-if="checkTransportError" severity="error" :closable="false">{{ checkTransportError }}</Message>

      <template v-if="checkResult">
        <Message
          v-if="!checkResult.valid"
          severity="error"
          :closable="false"
          data-testid="manifest-errors"
        >
          <ul class="check-errors">
            <li v-for="(err, idx) in checkResult.errors" :key="idx">
              <strong>{{ err.code }}</strong> {{ err.message }}
            </li>
          </ul>
        </Message>
        <Message v-else severity="success" :closable="false" data-testid="manifest-plan">
          <p class="plan-heading">Valid. Promote plan for "{{ checkAlias || 'live' }}":</p>
          <ul class="plan-lines">
            <li v-for="(line, idx) in planLines" :key="idx">{{ line }}</li>
          </ul>
        </Message>
      </template>
    </FcFormSection>

    <template #footer>
      <FcFormActions :bordered="false">
        <Button label="Close" severity="secondary" outlined @click="emit('close')" />
        <Button
          label="Publish with this manifest"
          icon="pi pi-upload"
          data-testid="manifest-publish-button"
          @click="openPublish"
        />
      </FcFormActions>
    </template>

    <PublishVersionDrawer
      v-if="showPublishDrawer"
      :address="address"
      :initial-manifest="model"
      @close="showPublishDrawer = false"
      @published="onPublished"
    />
  </Drawer>
</template>

<style scoped>
.manifest-editor-header {
  min-width: 0;
}

.manifest-editor-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #1e293b;
}

.manifest-editor-subtitle {
  margin: 2px 0 0;
  font-size: 13px;
  color: #64748b;
}

.editor-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  gap: 12px;
}

.editor-toolbar-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.import-file-label input {
  font-size: 12px;
}

.manifest-json-textarea {
  width: 100%;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

.form-view-disabled {
  opacity: 0.6;
}

.form-fieldset {
  border: none;
  margin: 0;
  padding: 0;
}

.list-row {
  padding: 12px 0;
  border-bottom: 1px dashed #e2e8f0;
  margin-bottom: 12px;
}

.list-row:last-of-type {
  border-bottom: none;
}

.cors-subform {
  margin-top: 8px;
}

.cors-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  margin-bottom: 8px;
}

.validate-row {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  margin-bottom: 12px;
}

.check-errors,
.plan-lines {
  margin: 4px 0 0;
  padding-left: 18px;
}

.plan-lines li {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

.plan-heading {
  margin: 0 0 4px;
  font-weight: 600;
}
</style>
