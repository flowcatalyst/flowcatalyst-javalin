<script setup lang="ts">
// Create drawer (docs/spec/function-ui.md §2.1, gap 1 in docs/functions.md
// §12 — there was no "Create Function" action anywhere in the SPA until
// this component). Mirrors DispatchPoolCreateDrawer.vue / the function
// domain claim drawer's client-scoping pattern.
//
// Ownership mirrors CreateFunction.java (server/src/main/java/io/flowcatalyst/
// platform/function/operations/CreateFunction.java): `authorize` calls
// `Checks.checkScopeAccess(Auth.current(), blankToNull(cmd.clientId()))`,
// which for a non-anchor principal requires `clientId` to be that
// principal's OWN client (a blank/omitted clientId routes to the
// anchor-only branch and 403s as SCOPE_FORBIDDEN for anyone who isn't an
// anchor or super-admin). So a client-scoped user's function is always
// their own client's — sent automatically, never a choice in this form —
// and only an anchor gets the platform-owned/client-owned toggle.
import { computed, ref } from "vue";
import { toast } from "@/utils/errorBus";
import { ApiError } from "@/api/client";
import { functionsApi } from "@/api/functions";
import { useAuthStore } from "@/stores/auth";
import { userScope } from "@/stores/permissions";
import EntityDrawer from "@/components/drawer/EntityDrawer.vue";
import { useDrawerRoute } from "@/composables/useDrawerRoute";

const emit = defineEmits<{
	changed: [];
}>();

const authStore = useAuthStore();
const isAnchor = computed(() => userScope(authStore.user) === "anchor");

// DnsLabel.parse's own pattern (server/src/main/java/io/flowcatalyst/platform/
// function/DnsLabel.java): 1-63 chars of a-z, 0-9, '-', not starting or
// ending with '-'. Client-side validation only — the platform is still the
// source of truth and its own rejections (LABEL_INVALID etc.) surface below.
const LABEL_PATTERN = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;

const applicationCode = ref("");
const serviceName = ref("");
const name = ref("");
const description = ref("");
const runtime = ref<"jvm" | "wasm">("jvm");
const runtimeOptions = [
	{ label: "JVM", value: "jvm", disabled: false },
	{ label: "WASM", value: "wasm", disabled: false },
];

const platformOwned = ref(true);
const clientId = ref<string | null>(null);

// The address a submit will produce, previewed live from the three labels
// (spec: "show the resulting address live") — the exact FunctionAddress
// render (FunctionAddress#render), app.service.name.
const addressPreview = computed(() => {
	if (!applicationCode.value || !serviceName.value || !name.value) return null;
	return `${applicationCode.value}.${serviceName.value}.${name.value}`;
});

const dirty = computed(
	() =>
		applicationCode.value !== "" ||
		serviceName.value !== "" ||
		name.value !== "" ||
		description.value !== "" ||
		clientId.value !== null,
);

const drawer = ref<InstanceType<typeof EntityDrawer> | null>(null);
const { goToList, replaceToDetail } = useDrawerRoute({
	listPath: "/functions",
	paramKey: "address",
	dirty,
});

const submitting = ref(false);
const errorCode = ref<string | null>(null);
const errorMessage = ref<string | null>(null);
const fieldErrors = ref<Array<{ location?: string; message: string }>>([]);

const isFormValid = computed(() => {
	return (
		LABEL_PATTERN.test(applicationCode.value) &&
		LABEL_PATTERN.test(serviceName.value) &&
		LABEL_PATTERN.test(name.value) &&
		(!isAnchor.value || platformOwned.value || !!clientId.value)
	);
});

function clearError() {
	errorCode.value = null;
	errorMessage.value = null;
	fieldErrors.value = [];
}

function applyError(e: unknown) {
	if (e instanceof ApiError) {
		errorCode.value = e.code ?? null;
		errorMessage.value = e.message;
		const errs = (e.details?.["errors"] ?? []) as Array<{
			message?: string;
			location?: string;
		}>;
		fieldErrors.value = Array.isArray(errs)
			? errs
					.filter((fe): fe is { message: string; location?: string } => Boolean(fe?.message))
					.map((fe) => ({ location: fe.location, message: fe.message }))
			: [];
	} else {
		errorCode.value = null;
		errorMessage.value = e instanceof Error ? e.message : "Failed to create function";
		fieldErrors.value = [];
	}
}

async function onSubmit() {
	if (!isFormValid.value) return;
	submitting.value = true;
	clearError();
	try {
		const fn = await functionsApi.create({
			applicationCode: applicationCode.value,
			serviceName: serviceName.value,
			name: name.value,
			runtime: runtime.value,
			description: description.value || undefined,
			clientId: isAnchor.value
				? platformOwned.value
					? undefined
					: (clientId.value ?? undefined)
				: (authStore.user?.clientId ?? undefined),
		});
		toast.success("Success", `Function ${fn.address} created`);
		emit("changed");
		replaceToDetail(fn.address);
	} catch (e) {
		applyError(e);
	} finally {
		submitting.value = false;
	}
}
</script>

<template>
  <EntityDrawer
    ref="drawer"
    title="Create Function"
    subtitle="Register a new function address"
    :dirty="dirty"
    @close="goToList()"
  >
    <div class="form-section">
      <h3>Address</h3>

      <div class="form-row">
        <div class="form-field">
          <label>Application Code <span class="required">*</span></label>
          <InputText v-model="applicationCode" placeholder="acme" class="full-width" />
        </div>
        <div class="form-field">
          <label>Service <span class="required">*</span></label>
          <InputText v-model="serviceName" placeholder="default" class="full-width" />
        </div>
        <div class="form-field">
          <label>Name <span class="required">*</span></label>
          <InputText v-model="name" placeholder="hello" class="full-width" />
        </div>
      </div>

      <div class="address-preview">
        <span class="address-preview-label">Address:</span>
        <code v-if="addressPreview">{{ addressPreview }}</code>
        <span v-else class="unset">—</span>
      </div>
    </div>

    <div class="form-section">
      <h3>Runtime</h3>

      <div class="form-field">
        <label>Runtime <span class="required">*</span></label>
        <Select
          v-model="runtime"
          :options="runtimeOptions"
          optionLabel="label"
          optionValue="value"
          optionDisabled="disabled"
          class="full-width"
          appendTo="self"
        />
        <small class="hint">
          A WASM function is a module built with an Extism PDK — write one in Rust, or in
          JavaScript with <code>fcdev fn init --lang js</code>.
        </small>
      </div>
    </div>

    <div class="form-section">
      <h3>Details</h3>

      <div class="form-field">
        <label>Description</label>
        <Textarea v-model="description" class="full-width" rows="3" placeholder="Optional description..." />
      </div>
    </div>

    <div v-if="isAnchor" class="form-section">
      <h3>Owner</h3>

      <div class="form-field checkbox-field">
        <Checkbox v-model="platformOwned" :binary="true" inputId="platformOwned" />
        <label for="platformOwned">Platform-owned function</label>
      </div>
      <div v-if="!platformOwned" class="form-field">
        <label>Client <span class="required">*</span></label>
        <ClientSelect v-model="clientId" placeholder="Search for a client" />
      </div>
    </div>

    <Message v-if="errorMessage" severity="error" class="error-message">
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
          icon="pi pi-times"
          severity="secondary"
          outlined
          :disabled="submitting"
          @click="drawer?.close()"
        />
        <Button
          label="Create Function"
          icon="pi pi-check"
          :loading="submitting"
          :disabled="!isFormValid"
          @click="onSubmit"
        />
      </FcFormActions>
    </template>
  </EntityDrawer>
</template>

<style scoped>
.form-section {
  margin-bottom: 32px;
}

.form-section h3 {
  margin: 0 0 16px 0;
  font-size: 14px;
  font-weight: 600;
  color: #475569;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.form-field {
  margin-bottom: 20px;
}

.form-field > label {
  display: block;
  font-weight: 500;
  margin-bottom: 6px;
}

.form-field .required {
  color: #ef4444;
}

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 20px;
}

.full-width {
  width: 100%;
}

.hint {
  display: block;
  font-size: 12px;
  color: #64748b;
  margin-top: 4px;
}

.address-preview {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #475569;
}

.address-preview-label {
  font-weight: 500;
}

.address-preview .unset {
  color: #94a3b8;
  font-style: italic;
}

.checkbox-field {
  display: flex;
  align-items: center;
  gap: 8px;
}

.checkbox-field label {
  margin: 0;
  cursor: pointer;
}

.error-message {
  margin-bottom: 16px;
}

.field-errors {
  margin: 8px 0 0;
  padding-left: 18px;
  font-size: 13px;
}

@media (max-width: 640px) {
  .form-row {
    grid-template-columns: 1fr;
  }
}
</style>
