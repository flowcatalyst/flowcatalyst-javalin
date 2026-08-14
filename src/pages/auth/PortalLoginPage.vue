<script setup lang="ts">
// Portal-plane login (docs/portal-identity-plan.md Phase 2.5 v2): reached
// via GET /portal/authorize?flow=… — a portal app's OAuth entry. Portal
// identities are a separate population from platform users, so this page
// has NO guest guard and never touches the platform session: every visit
// authenticates fresh and ends in a redirect back to the portal app.
import { ref, computed, onMounted } from "vue";
import { useRoute } from "vue-router";
import { useLoginThemeStore } from "@/stores/loginTheme";

const route = useRoute();
const themeStore = useLoginThemeStore();

const flowId = ref<string>("");
const email = ref("");
const password = ref("");
// step: "email" → check-domain → "password" (or SSO redirect) → done
const step = ref<"email" | "password" | "expired">("email");
const submitting = ref(false);
const error = ref<string | null>(null);

const emailValid = computed(() => /.+@.+\..+/.test(email.value.trim()));

onMounted(async () => {
	await themeStore.loadTheme();
	themeStore.applyThemeColors();
	const flow = route.query["flow"];
	if (typeof flow === "string" && flow !== "") {
		flowId.value = flow;
	} else {
		step.value = "expired";
	}
});

interface PortalError {
	code?: string;
	message?: string;
}

async function portalPost<T>(path: string, body: unknown): Promise<T> {
	const res = await fetch(path, {
		method: "POST",
		headers: { "Content-Type": "application/json" },
		body: JSON.stringify(body),
	});
	const data = (await res.json().catch(() => ({}))) as T & PortalError;
	if (!res.ok) {
		if (data.code === "FLOW_EXPIRED") {
			step.value = "expired";
		}
		throw new Error(data.message || "Request failed");
	}
	return data;
}

async function continueWithEmail() {
	if (!emailValid.value || submitting.value) return;
	submitting.value = true;
	error.value = null;
	try {
		const result = await portalPost<{ method: string; redirectUrl?: string }>(
			"/portal/auth/check-domain",
			{ flowId: flowId.value, email: email.value.trim() },
		);
		if (result.method === "SSO" && result.redirectUrl) {
			// Org-federated: hand off to the organisation's identity provider.
			window.location.assign(result.redirectUrl);
			return;
		}
		step.value = "password";
	} catch (e) {
		if (step.value !== "expired") {
			error.value = e instanceof Error ? e.message : "Something went wrong";
		}
	} finally {
		submitting.value = false;
	}
}

async function signIn() {
	if (!password.value || submitting.value) return;
	submitting.value = true;
	error.value = null;
	try {
		const result = await portalPost<{ redirectUrl: string }>(
			"/portal/auth/login",
			{ flowId: flowId.value, email: email.value.trim(), password: password.value },
		);
		window.location.assign(result.redirectUrl);
	} catch (e) {
		if (step.value !== "expired") {
			error.value = e instanceof Error ? e.message : "Sign-in failed";
		}
	} finally {
		submitting.value = false;
	}
}
</script>

<template>
  <div class="login-container" :style="{ background: themeStore.background }">
    <div class="login-content">
      <div class="login-header">
        <img
          v-if="themeStore.theme.logoUrl"
          :src="themeStore.theme.logoUrl"
          class="logo-image"
          alt="Logo"
        />
        <h1>Sign in</h1>
      </div>

      <div class="login-card">
        <template v-if="step === 'expired'">
          <p class="portal-message">
            This sign-in link has expired. Please return to the application and
            try again.
          </p>
        </template>

        <template v-else>
          <form v-if="step === 'email'" @submit.prevent="continueWithEmail">
            <div class="field">
              <label for="portalEmail">Email</label>
              <InputText
                id="portalEmail"
                v-model="email"
                type="email"
                autocomplete="email"
                autofocus
                class="w-full"
              />
            </div>
            <Button
              type="submit"
              label="Continue"
              class="w-full"
              :loading="submitting"
              :disabled="!emailValid"
            />
          </form>

          <form v-else @submit.prevent="signIn">
            <div class="field">
              <label for="portalEmailRo">Email</label>
              <InputText id="portalEmailRo" :model-value="email" disabled class="w-full" />
            </div>
            <div class="field">
              <label for="portalPassword">Password</label>
              <Password
                id="portalPassword"
                v-model="password"
                :feedback="false"
                toggle-mask
                autocomplete="current-password"
                input-class="w-full"
                class="w-full"
              />
            </div>
            <Button
              type="submit"
              label="Sign in"
              class="w-full"
              :loading="submitting"
              :disabled="!password"
            />
            <Button
              label="Use a different email"
              text
              class="w-full portal-back"
              @click="step = 'email'; password = ''; error = null"
            />
          </form>

          <Message v-if="error" severity="error" :closable="false" class="portal-error">
            {{ error }}
          </Message>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-container {
	min-height: 100vh;
	display: flex;
	align-items: center;
	justify-content: center;
	padding: 1rem;
}
.login-content {
	width: 100%;
	max-width: 380px;
}
.login-header {
	text-align: center;
	margin-bottom: 1.5rem;
}
.login-header h1 {
	font-size: 1.25rem;
	margin-top: 0.75rem;
}
.logo-image {
	max-height: 48px;
}
.login-card {
	background: var(--p-surface-0, #fff);
	border-radius: 8px;
	padding: 1.5rem;
	box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
}
.field {
	margin-bottom: 1rem;
	display: flex;
	flex-direction: column;
	gap: 0.35rem;
}
.portal-message {
	text-align: center;
	margin: 0.5rem 0;
}
.portal-error {
	margin-top: 1rem;
}
.portal-back {
	margin-top: 0.5rem;
}
</style>
