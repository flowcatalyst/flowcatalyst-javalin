<script setup lang="ts">
// Claim drawer (docs/spec/function-ui.md §2.3): hostname (+ client for an
// anchor). A claim is verified by being made — no DNS record, no dev-mode
// special case (docs/spec/function-domains-no-dns.md).
import { computed, ref } from "vue";
import { toast } from "@/utils/errorBus";
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

const hostname = ref("");
const platformOwned = ref(true);
const clientId = ref<string | null>(null);

const dirty = computed(() => hostname.value !== "" || clientId.value !== null);

const drawer = ref<InstanceType<typeof EntityDrawer> | null>(null);
const { goToList, replaceToDetail } = useDrawerRoute({
	listPath: "/function-domains",
	paramKey: "hostname",
	dirty,
});

const submitting = ref(false);
const errorMessage = ref<string | null>(null);

const isFormValid = computed(
	() => hostname.value.trim().length > 0 && (!isAnchor.value || platformOwned.value || !!clientId.value),
);

async function onSubmit() {
	if (!isFormValid.value) return;
	submitting.value = true;
	errorMessage.value = null;
	try {
		const domain = await functionsApi.claimDomain({
			hostname: hostname.value.trim(),
			clientId: isAnchor.value
				? platformOwned.value
					? undefined
					: (clientId.value ?? undefined)
				: (authStore.user?.clientId ?? undefined),
		});
		toast.success("Success", `${domain.hostname} claimed`);
		emit("changed");
		void replaceToDetail(domain.hostname);
	} catch (e) {
		errorMessage.value = e instanceof Error ? e.message : "Failed to claim domain";
	} finally {
		submitting.value = false;
	}
}
</script>

<template>
  <EntityDrawer
    ref="drawer"
    title="Claim Domain"
    subtitle="Claim a zone for a function's public routes"
    :dirty="dirty"
    @close="goToList()"
  >
    <div class="form-field">
      <label>Hostname <span class="required">*</span></label>
      <InputText
        v-model="hostname"
        placeholder="api.acme.com, or hello.localhost for dev"
        class="full-width"
      />
      <small class="hint">
        A claim covers every hostname under it — claiming <code>acme.com</code> also covers
        <code>myapp.acme.com</code>. It is usable immediately, no DNS record needed.
      </small>
    </div>

    <template v-if="isAnchor">
      <div class="form-field checkbox-field">
        <Checkbox v-model="platformOwned" :binary="true" inputId="platformOwned" />
        <label for="platformOwned">Platform-owned domain</label>
      </div>
      <div v-if="!platformOwned" class="form-field">
        <label>Client <span class="required">*</span></label>
        <ClientSelect v-model="clientId" placeholder="Search for a client" />
      </div>
    </template>

    <Message v-if="errorMessage" severity="error" class="error-message">
      {{ errorMessage }}
    </Message>

    <template #footer>
      <FcFormActions :bordered="false">
        <Button
          label="Cancel"
          severity="secondary"
          outlined
          :disabled="submitting"
          @click="drawer?.close()"
        />
        <Button
          label="Claim"
          :loading="submitting"
          :disabled="!isFormValid"
          @click="onSubmit"
        />
      </FcFormActions>
    </template>
  </EntityDrawer>
</template>

<style scoped>
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

.full-width {
  width: 100%;
}

.hint {
  display: block;
  font-size: 12px;
  color: #64748b;
  margin-top: 4px;
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
</style>
