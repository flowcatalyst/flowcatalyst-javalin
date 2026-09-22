<script setup lang="ts">
// Policy detail drawer (docs/spec/function-ui.md §2.4): edits the signer
// list and the limit ceilings, with the platform's own defaults shown
// beside each ceiling. A client owner with no stored policy reads as
// "platform defaults — no policy", with Create in place of Save.
import { ref, watch } from "vue";
import { toast } from "@/utils/errorBus";
import {
	functionsApi,
	type PolicyResponse,
	type PolicySignerRequest,
} from "@/api/functions";
import { clientsApi } from "@/api/clients";
import EntityDrawer from "@/components/drawer/EntityDrawer.vue";
import { useDrawerRoute } from "@/composables/useDrawerRoute";
import { useDirtyForm } from "@/composables/useDirtyForm";

const emit = defineEmits<{
	changed: [];
}>();

const drawer = ref<InstanceType<typeof EntityDrawer> | null>(null);
const { id: owner, goToList } = useDrawerRoute({
	listPath: "/function-policies",
	paramKey: "owner",
});

const loading = ref(true);
const loadError = ref<string | null>(null);
const policy = ref<PolicyResponse | null>(null);
const platformDefaults = ref<PolicyResponse | null>(null);
const ownerName = ref<string | null>(null);
const saving = ref(false);

interface SignerRow {
	issuer: string;
	subject: string;
}

const signers = ref<SignerRow[]>([]);
const maxDurationMs = ref<number | null>(null);
const maxConcurrency = ref<number | null>(null);
const maxWasmMemoryMb = ref<number | null>(null);
const maxDbPoolSize = ref<number | null>(null);

const { dirty, markClean, reset: resetDirtyTracking } = useDirtyForm(() => ({
	signers: signers.value,
	maxDurationMs: maxDurationMs.value,
	maxConcurrency: maxConcurrency.value,
	maxWasmMemoryMb: maxWasmMemoryMb.value,
	maxDbPoolSize: maxDbPoolSize.value,
}));

watch(
	owner,
	async (value) => {
		if (!value) return;
		await load(value);
	},
	{ immediate: true },
);

async function load(o: string) {
	loading.value = true;
	loadError.value = null;
	ownerName.value = null;
	try {
		policy.value = await functionsApi.getPolicy(o);
		if (o === "platform") {
			platformDefaults.value = null;
		} else {
			try {
				const client = await clientsApi.get(o);
				ownerName.value = client.name;
			} catch {
				// fall back to the raw owner id below
			}
			try {
				platformDefaults.value = await functionsApi.getPolicy("platform");
			} catch {
				platformDefaults.value = null;
			}
		}
		resetForm();
	} catch {
		policy.value = null;
		loadError.value = "Could not load this owner's policy";
	} finally {
		loading.value = false;
	}
}

function resetForm() {
	if (!policy.value) return;
	signers.value = policy.value.signers.map((s) => ({ issuer: s.issuer, subject: s.subject }));
	maxDurationMs.value = policy.value.ceilings.maxDurationMs;
	maxConcurrency.value = policy.value.ceilings.maxConcurrency;
	maxWasmMemoryMb.value = policy.value.ceilings.maxWasmMemoryMb;
	maxDbPoolSize.value = policy.value.ceilings.maxDbPoolSize;
	resetDirtyTracking();
	markClean();
}

function ownerLabel(): string {
	if (!owner.value) return "";
	if (owner.value === "platform") return "Platform";
	return ownerName.value ?? owner.value;
}

function addSigner() {
	signers.value.push({ issuer: "", subject: "" });
}
function removeSigner(index: number) {
	signers.value.splice(index, 1);
}

async function save() {
	if (!owner.value) return;
	saving.value = true;
	try {
		const body = {
			signers: signers.value
				.filter((s) => s.issuer.trim() && s.subject.trim())
				.map(
					(s): PolicySignerRequest => ({
						issuer: s.issuer.trim(),
						subject: s.subject.trim(),
					}),
				),
			ceilings: {
				maxDurationMs: maxDurationMs.value ?? undefined,
				maxConcurrency: maxConcurrency.value ?? undefined,
				maxWasmMemoryMb: maxWasmMemoryMb.value ?? undefined,
				maxDbPoolSize: maxDbPoolSize.value ?? undefined,
			},
		};
		policy.value = await functionsApi.putPolicy(owner.value, body);
		resetForm();
		toast.success("Success", policy.value.stored ? "Policy saved" : "Policy created");
		emit("changed");
	} catch {
		// errors surface via the global error toast
	} finally {
		saving.value = false;
	}
}
</script>

<template>
  <EntityDrawer
    ref="drawer"
    :title="ownerLabel() || 'Function Policy'"
    :subtitle="owner"
    :loading="loading"
    :error="loadError"
    :dirty="dirty"
    @close="goToList()"
  >
    <template v-if="policy" #header-extra>
      <Tag
        :value="policy.stored ? 'Custom Policy' : 'Platform Defaults — No Policy'"
        :severity="policy.stored ? 'info' : 'secondary'"
      />
    </template>

    <template v-if="policy">
      <Message
        v-if="!policy.stored"
        severity="secondary"
        :closable="false"
        class="defaults-banner"
      >
        This owner has no stored policy — the platform defaults apply. Save below to create one.
      </Message>

      <FcFormSection title="Signers" flat>
        <template #actions>
          <Button icon="pi pi-plus" label="Add Signer" text @click="addSigner" />
        </template>
        <p v-if="signers.length === 0" class="empty-hint">
          No permitted signer identities — every publish is refused until at least one is added.
        </p>
        <div v-for="(signer, index) in signers" :key="index" class="signer-row">
          <InputText v-model="signer.issuer" placeholder="Issuer (e.g. https://fulcio.sigstore.dev)" />
          <InputText v-model="signer.subject" placeholder="Subject (e.g. an email or workflow URI)" />
          <Button icon="pi pi-trash" text severity="danger" @click="removeSigner(index)" />
        </div>
      </FcFormSection>

      <FcFormSection title="Limit Ceilings" flat>
        <div class="fc-form-grid">
          <FcFormField label="Max Duration (ms)">
            <template #default="{ id: fieldId }">
              <InputNumber :inputId="fieldId" v-model="maxDurationMs" :min="1" />
              <small v-if="platformDefaults" class="default-hint">
                platform default: {{ platformDefaults.ceilings.maxDurationMs }} ms
              </small>
            </template>
          </FcFormField>
          <FcFormField label="Max Concurrency">
            <template #default="{ id: fieldId }">
              <InputNumber :inputId="fieldId" v-model="maxConcurrency" :min="1" />
              <small v-if="platformDefaults" class="default-hint">
                platform default: {{ platformDefaults.ceilings.maxConcurrency }}
              </small>
            </template>
          </FcFormField>
          <FcFormField label="Max Wasm Memory (MB)">
            <template #default="{ id: fieldId }">
              <InputNumber :inputId="fieldId" v-model="maxWasmMemoryMb" :min="1" />
              <small v-if="platformDefaults" class="default-hint">
                platform default: {{ platformDefaults.ceilings.maxWasmMemoryMb }} MB
              </small>
            </template>
          </FcFormField>
          <FcFormField label="Max DB Pool Size">
            <template #default="{ id: fieldId }">
              <InputNumber :inputId="fieldId" v-model="maxDbPoolSize" :min="1" />
              <small v-if="platformDefaults" class="default-hint">
                platform default: {{ platformDefaults.ceilings.maxDbPoolSize }}
              </small>
            </template>
          </FcFormField>
        </div>
      </FcFormSection>
    </template>

    <template #footer>
      <FcFormActions :bordered="false">
        <Button v-if="dirty" label="Discard" severity="secondary" outlined @click="resetForm" />
        <Button
          :label="policy?.stored ? 'Save' : 'Create'"
          :disabled="!dirty"
          :loading="saving"
          @click="save"
        />
      </FcFormActions>
    </template>
  </EntityDrawer>
</template>

<style scoped>
.defaults-banner {
  margin-bottom: 16px;
}

.empty-hint {
  color: #64748b;
  font-size: 13px;
}

.signer-row {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.default-hint {
  display: block;
  margin-top: 4px;
  color: #94a3b8;
  font-size: 11px;
}
</style>
