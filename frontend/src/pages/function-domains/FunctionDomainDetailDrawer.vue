<script setup lang="ts">
// Domain detail drawer (docs/spec/function-ui.md §2.3): the TXT record,
// Verify, Release.
//
// `GET /api/function-domains/{hostname}` (S3) resolves by hostname alone —
// reach-gated server-side — so a deep link needs no owner context carried
// across navigation.
import { computed, ref, watch } from "vue";
import { toast } from "@/utils/errorBus";
import { useConfirm } from "primevue/useconfirm";
import { ApiError } from "@/api/client";
import { functionsApi, type DomainResponse } from "@/api/functions";
import { useAuthStore } from "@/stores/auth";
import { userHasPermission } from "@/stores/permissions";
import EntityDrawer from "@/components/drawer/EntityDrawer.vue";
import { useDrawerRoute } from "@/composables/useDrawerRoute";

const emit = defineEmits<{
	changed: [];
}>();

const confirm = useConfirm();
const authStore = useAuthStore();

const canManage = computed(() =>
	userHasPermission(authStore.user, "platform:function:domain:manage"),
);

const drawer = ref<InstanceType<typeof EntityDrawer> | null>(null);
const { id: hostname, goToList } = useDrawerRoute({
	listPath: "/function-domains",
	paramKey: "hostname",
});

const loading = ref(true);
const loadError = ref<string | null>(null);
const domain = ref<DomainResponse | null>(null);

const verifying = ref(false);
const verifyError = ref<string | null>(null);
const releaseError = ref<string | null>(null);

watch(
	hostname,
	async (value) => {
		if (!value) return;
		await load(value);
	},
	{ immediate: true },
);

async function load(h: string) {
	loading.value = true;
	loadError.value = null;
	verifyError.value = null;
	releaseError.value = null;
	try {
		domain.value = await functionsApi.getDomain(h);
	} catch {
		domain.value = null;
		loadError.value = "Domain not found";
	} finally {
		loading.value = false;
	}
}

function isLocalhost(h: string): boolean {
	return h === "localhost" || h.endsWith(".localhost");
}

function copy(text: string, label: string) {
	void navigator.clipboard.writeText(text);
	toast.info("Copied", `${label} copied to clipboard`);
}

async function verify() {
	if (!domain.value) return;
	verifying.value = true;
	verifyError.value = null;
	try {
		domain.value = await functionsApi.verifyDomain(domain.value.hostname);
		toast.success("Success", "Domain verified");
		emit("changed");
	} catch (e) {
		verifyError.value =
			e instanceof ApiError ? `${e.code ?? ""} ${e.message}`.trim() : "Verification failed";
	} finally {
		verifying.value = false;
	}
}

function confirmRelease() {
	if (!domain.value) return;
	confirm.require({
		message:
			`Release ${domain.value.hostname}? This cannot be undone, and is refused while any ` +
			"live function manifest still routes to it.",
		header: "Release Domain",
		icon: "pi pi-exclamation-triangle",
		acceptLabel: "Release",
		acceptClass: "p-button-danger",
		accept: release,
	});
}

async function release() {
	if (!domain.value) return;
	releaseError.value = null;
	try {
		await functionsApi.releaseDomain(domain.value.hostname);
		toast.success("Success", "Domain released");
		emit("changed");
		void drawer.value?.close(true);
	} catch (e) {
		releaseError.value =
			e instanceof ApiError ? `${e.code ?? ""} ${e.message}`.trim() : "Release failed";
	}
}

function formatDate(s?: string): string {
	if (!s) return "—";
	return new Date(s).toLocaleString();
}
</script>

<template>
  <EntityDrawer
    ref="drawer"
    :title="domain?.hostname || 'Domain'"
    :loading="loading"
    :error="loadError"
    @close="goToList()"
  >
    <template v-if="domain" #header-extra>
      <Tag
        :value="domain.verification.state"
        :severity="domain.verification.state === 'VERIFIED' ? 'success' : 'warn'"
      />
    </template>

    <template v-if="domain">
      <FcFormSection title="Details" flat>
        <div class="fc-detail-grid">
          <FcDetailField label="Hostname">
            <code>{{ domain.hostname }}</code>
          </FcDetailField>
          <FcDetailField label="Owner" :value="domain.owner" />
          <FcDetailField label="Claimed" :value="formatDate(domain.createdAt)" />
        </div>
      </FcFormSection>

      <FcFormSection title="Verification" flat>
        <p v-if="isLocalhost(domain.hostname)" class="localhost-note">
          Auto-verified in dev mode — <code>.localhost</code> always resolves to loopback, no DNS
          record is needed.
        </p>
        <template v-else-if="domain.verification.record">
          <p class="txt-intro">Create this DNS record to verify ownership:</p>
          <div class="txt-record">
            <div class="txt-row">
              <span class="txt-label">Type</span>
              <code>{{ domain.verification.record.type }}</code>
            </div>
            <div class="txt-row">
              <span class="txt-label">Name</span>
              <code>{{ domain.verification.record.name }}</code>
              <Button
                icon="pi pi-copy"
                text
                size="small"
                @click="copy(domain.verification.record.name, 'Record name')"
              />
            </div>
            <div class="txt-row">
              <span class="txt-label">Value</span>
              <code>{{ domain.verification.record.value }}</code>
              <Button
                icon="pi pi-copy"
                text
                size="small"
                @click="copy(domain.verification.record.value, 'Record value')"
              />
            </div>
          </div>
        </template>
        <p v-else-if="domain.verification.state === 'VERIFIED'" class="localhost-note">
          Verified.
        </p>

        <Message v-if="verifyError" severity="error" :closable="false" class="inline-error">
          {{ verifyError }}
        </Message>

        <div v-if="canManage && domain.verification.state !== 'VERIFIED' && !isLocalhost(domain.hostname)" class="verify-action">
          <Button label="Verify" icon="pi pi-check" :loading="verifying" @click="verify" />
        </div>
      </FcFormSection>

      <FcFormSection v-if="canManage" title="Actions" flat>
        <Message v-if="releaseError" severity="error" :closable="false" class="inline-error">
          {{ releaseError }}
        </Message>
        <div class="action-items">
          <div class="action-item">
            <div class="action-info">
              <strong>Release Domain</strong>
              <p>Frees this hostname. Refused while a live manifest still routes to it.</p>
            </div>
            <Button
              label="Release"
              icon="pi pi-trash"
              severity="danger"
              outlined
              @click="confirmRelease"
            />
          </div>
        </div>
      </FcFormSection>
    </template>
  </EntityDrawer>
</template>

<style scoped>
.localhost-note {
  color: #475569;
  font-size: 13px;
}

.txt-intro {
  font-size: 13px;
  color: #475569;
  margin: 0 0 8px;
}

.txt-record {
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: #fafafa;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 12px;
}

.txt-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.txt-label {
  min-width: 48px;
  color: #64748b;
  font-weight: 600;
}

.verify-action {
  margin-top: 12px;
}

.inline-error {
  margin-top: 12px;
}

.action-items {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.action-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 16px;
  background: #fafafa;
  border-radius: 8px;
  border: 1px solid #e5e7eb;
}

.action-info strong {
  display: block;
  margin-bottom: 4px;
}

.action-info p {
  margin: 0;
  font-size: 13px;
  color: #64748b;
}
</style>
