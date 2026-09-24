<script setup lang="ts">
// The publish drawer (docs/spec/function-ui.md §2.2). Triggered from the
// Versions tab, not a routed drawer — there is no "/functions/:address/
// versions/new" URL, so this is a plain overlay the Versions tab shows/hides.
//
// Flow (U3): sha256 the jar in the browser, upload it FIRST, then publish
// with the artifactRef the UPLOAD returned (never a locally built
// "platform://..." string) — the platform is the only party that knows the
// real ref for a given store.
import { computed, ref } from "vue";
import { toast } from "@/utils/errorBus";
import { ApiError } from "@/api/client";
import {
	functionsApi,
	type PublishManifestRequest,
	type PublishResponse,
} from "@/api/functions";

const props = defineProps<{
	address: string;
	/**
	 * The manifest editor's "Publish with this manifest" (docs/spec/
	 * function-manifest-authoring.md M4): pre-fills the manifest text with
	 * the editor's current model — the jar is still chosen here as usual.
	 * `manifestFile`/its file input stay untouched: only the text seeds.
	 */
	initialManifest?: PublishManifestRequest | null;
}>();

const emit = defineEmits<{
	close: [];
	published: [version: PublishResponse];
}>();

const jarFile = ref<File | null>(null);
const manifestFile = ref<File | null>(null);
const manifestText = ref<string>(
	props.initialManifest ? JSON.stringify(props.initialManifest, null, 2) : "",
);
const manifestParseError = ref<string | null>(null);
const bundleFile = ref<File | null>(null);

const submitting = ref(false);
const uploadingLabel = ref<string | null>(null);

const errorCode = ref<string | null>(null);
const errorMessage = ref<string | null>(null);
const fieldErrors = ref<Array<{ location?: string; message: string }>>([]);

const parsedManifest = computed<PublishManifestRequest | null>(() => {
	if (!manifestText.value.trim()) return null;
	try {
		const parsed = JSON.parse(manifestText.value);
		manifestParseError.value = null;
		return parsed as PublishManifestRequest;
	} catch {
		manifestParseError.value = "manifest.json is not valid JSON";
		return null;
	}
});

const canSubmit = computed(
	() =>
		!!jarFile.value &&
		!!manifestText.value.trim() &&
		!manifestParseError.value &&
		!submitting.value,
);

async function onJarChange(event: Event) {
	const input = event.target as HTMLInputElement;
	jarFile.value = input.files?.[0] ?? null;
}

async function onManifestChange(event: Event) {
	const input = event.target as HTMLInputElement;
	const file = input.files?.[0] ?? null;
	manifestFile.value = file;
	if (!file) {
		manifestText.value = "";
		return;
	}
	manifestText.value = await file.text();
}

async function onBundleChange(event: Event) {
	const input = event.target as HTMLInputElement;
	bundleFile.value = input.files?.[0] ?? null;
}

async function sha256Hex(bytes: ArrayBuffer): Promise<string> {
	const digest = await crypto.subtle.digest("SHA-256", bytes);
	return Array.from(new Uint8Array(digest))
		.map((b) => b.toString(16).padStart(2, "0"))
		.join("");
}

function clearError() {
	errorCode.value = null;
	errorMessage.value = null;
	fieldErrors.value = [];
}

function applyError(e: unknown) {
	if (e instanceof ApiError) {
		errorCode.value = e.code ?? null;
		if (e.code === "ARTIFACT_STORE_NOT_CONFIGURED") {
			errorMessage.value = "the platform has no artifact store configured";
		} else {
			errorMessage.value = e.message;
		}
		const errs = (e.details?.["errors"] ?? []) as Array<{
			message?: string;
			location?: string;
		}>;
		fieldErrors.value = Array.isArray(errs)
			? errs
					.filter((fe): fe is { message: string; location?: string } =>
						Boolean(fe?.message),
					)
					.map((fe) => ({ location: fe.location, message: fe.message }))
			: [];
	} else {
		errorCode.value = null;
		errorMessage.value = e instanceof Error ? e.message : "Publish failed";
		fieldErrors.value = [];
	}
}

async function onSubmit() {
	const manifest = parsedManifest.value;
	const jar = jarFile.value;
	if (!jar || !manifest || !canSubmit.value) return;

	clearError();
	submitting.value = true;
	try {
		uploadingLabel.value = "Hashing artifact…";
		const bytes = await jar.arrayBuffer();
		const digest = `sha256:${await sha256Hex(bytes)}`;

		uploadingLabel.value = "Uploading artifact…";
		let uploadResult: { artifactRef: string };
		try {
			uploadResult = await functionsApi.uploadArtifact(
				props.address,
				digest,
				bytes,
			);
		} catch (e) {
			applyError(e);
			return;
		}

		uploadingLabel.value = "Publishing version…";
		const signatureBundle = bundleFile.value
			? await bundleFile.value.text()
			: undefined;
		const published = await functionsApi.publishVersion(props.address, {
			artifactRef: uploadResult.artifactRef,
			digest,
			manifest,
			signatureBundle,
		});

		toast.success("Success", `Version ${published.version} published`);
		emit("published", published);
		emit("close");
	} catch (e) {
		applyError(e);
	} finally {
		submitting.value = false;
		uploadingLabel.value = null;
	}
}
</script>

<template>
  <Drawer
    :visible="true"
    position="right"
    :modal="false"
    :block-scroll="false"
    :dismissable="false"
    class="entity-drawer entity-drawer-two-thirds publish-version-drawer"
    @update:visible="(v: boolean) => !v && emit('close')"
  >
    <template #header>
      <div class="publish-drawer-header">
        <h2 class="publish-drawer-title">Publish Version</h2>
        <p class="publish-drawer-subtitle">{{ address }}</p>
      </div>
    </template>

    <FcFormSection title="Artifact" flat>
      <div class="fc-form-grid">
        <FcFormField label="Jar file" required span>
          <template #default>
            <input
              type="file"
              accept=".jar,application/java-archive,application/octet-stream"
              data-testid="publish-jar-input"
              @change="onJarChange"
            />
            <small v-if="jarFile" class="file-hint">{{ jarFile.name }}</small>
          </template>
        </FcFormField>

        <FcFormField label="manifest.json" required span>
          <template #default>
            <input
              type="file"
              accept=".json,application/json"
              data-testid="publish-manifest-input"
              @change="onManifestChange"
            />
            <small v-if="manifestFile" class="file-hint">{{ manifestFile.name }}</small>
            <small
              v-else-if="manifestText"
              class="file-hint"
              data-testid="publish-manifest-prefilled-hint"
            >pre-filled from the manifest editor — choose a file to replace it</small>
            <small v-if="manifestParseError" class="p-error">{{ manifestParseError }}</small>
          </template>
        </FcFormField>

        <FcFormField label="Sigstore bundle (optional)" span>
          <template #default>
            <input
              type="file"
              accept=".json,application/json"
              data-testid="publish-bundle-input"
              @change="onBundleChange"
            />
            <small v-if="bundleFile" class="file-hint">{{ bundleFile.name }}</small>
          </template>
        </FcFormField>
      </div>
    </FcFormSection>

    <div v-if="submitting" class="publish-progress" data-testid="publish-progress">
      <ProgressSpinner style="width: 20px; height: 20px" />
      <span>{{ uploadingLabel }}</span>
    </div>

    <Message
      v-if="errorMessage"
      severity="error"
      :closable="false"
      class="publish-error"
      data-testid="publish-error"
    >
      <div>
        <strong v-if="errorCode">{{ errorCode }}</strong>
        <span> {{ errorMessage }}</span>
      </div>
      <ul v-if="fieldErrors.length" class="field-errors">
        <li v-for="(fe, idx) in fieldErrors" :key="idx">
          <template v-if="fe.location">{{ fe.location }}: </template>{{ fe.message }}
        </li>
      </ul>
    </Message>

    <template #footer>
      <FcFormActions :bordered="false">
        <Button
          label="Cancel"
          severity="secondary"
          outlined
          :disabled="submitting"
          @click="emit('close')"
        />
        <Button
          label="Publish"
          :loading="submitting"
          :disabled="!canSubmit"
          data-testid="publish-submit"
          @click="onSubmit"
        />
      </FcFormActions>
    </template>
  </Drawer>
</template>

<style scoped>
.publish-drawer-header {
  min-width: 0;
}

.publish-drawer-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #1e293b;
}

.publish-drawer-subtitle {
  margin: 2px 0 0;
  font-size: 13px;
  color: #64748b;
}

.file-hint {
  display: block;
  margin-top: 4px;
  color: #64748b;
}

.publish-progress {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 0;
  font-size: 13px;
  color: #475569;
}

.publish-error {
  margin-top: 12px;
}

.field-errors {
  margin: 8px 0 0;
  padding-left: 18px;
  font-size: 13px;
}
</style>
