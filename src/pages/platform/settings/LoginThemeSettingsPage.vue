<script setup lang="ts">
import { toast } from "@/utils/errorBus";
import { ref, onMounted, computed } from "vue";
import { configApi, type LoginTheme } from "@/api/config";
import { getErrorMessage } from "@/utils/errors";


const loading = ref(true);
const saving = ref(false);
const error = ref<string | null>(null);

// Form state
const theme = ref<LoginTheme>({
	brandName: "FlowCatalyst",
	brandSubtitle: "Platform Administration",
	logoUrl: null,
	logoSvg: null,
	logoHeight: 40,
	primaryColor: "#102a43",
	accentColor: "#0967d2",
	backgroundColor: "#0a1929",
	backgroundGradient: "linear-gradient(135deg, #102a43 0%, #0a1929 100%)",
	footerText: "Secure access to your FlowCatalyst platform",
	customCss: null,
});

// Color picker values (without #)
const primaryColorPicker = ref("102a43");
const accentColorPicker = ref("0967d2");
const backgroundColorPicker = ref("0a1929");

// Preview background
const previewBackground = computed(
	() => theme.value.backgroundGradient || theme.value.backgroundColor,
);

onMounted(async () => {
	await loadTheme();
});

async function loadTheme() {
	loading.value = true;
	error.value = null;

	try {
		// Try to get existing theme config
		const themeJson = await configApi.getLoginThemeConfig();
		if (themeJson) {
			const parsed = JSON.parse(themeJson);
			theme.value = { ...theme.value, ...parsed };
			// Update color pickers
			primaryColorPicker.value = theme.value.primaryColor.replace("#", "");
			accentColorPicker.value = theme.value.accentColor.replace("#", "");
			backgroundColorPicker.value = theme.value.backgroundColor.replace(
				"#",
				"",
			);
		}
	} catch (e) {
		// No existing config, use defaults
		console.log("No existing theme config, using defaults");
	} finally {
		loading.value = false;
	}
}

function onPrimaryColorChange(color: string) {
	theme.value.primaryColor = "#" + color;
	updateGradient();
}

function onAccentColorChange(color: string) {
	theme.value.accentColor = "#" + color;
}

function onBackgroundColorChange(color: string) {
	theme.value.backgroundColor = "#" + color;
	updateGradient();
}

function updateGradient() {
	// Auto-generate gradient from primary and background colors
	theme.value.backgroundGradient = `linear-gradient(135deg, ${theme.value.primaryColor} 0%, ${theme.value.backgroundColor} 100%)`;
}

async function saveTheme() {
	saving.value = true;
	error.value = null;

	try {
		await configApi.setLoginThemeConfig(theme.value);
		toast.success("Success", "Theme saved successfully");
	} catch (e: unknown) {
		error.value = getErrorMessage(e, "Failed to save theme");
	} finally {
		saving.value = false;
	}
}

function resetToDefaults() {
	theme.value = {
		brandName: "FlowCatalyst",
		brandSubtitle: "Platform Administration",
		logoUrl: null,
		logoSvg: null,
		logoHeight: 40,
		primaryColor: "#102a43",
		accentColor: "#0967d2",
		backgroundColor: "#0a1929",
		backgroundGradient: "linear-gradient(135deg, #102a43 0%, #0a1929 100%)",
		footerText: "Secure access to your FlowCatalyst platform",
		customCss: null,
	};
	primaryColorPicker.value = "102a43";
	accentColorPicker.value = "0967d2";
	backgroundColorPicker.value = "0a1929";
}
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">Theme Settings</h1>
        <p class="page-subtitle">Customize the appearance of the platform with your branding.</p>
      </div>
    </header>

    <Message v-if="error" severity="error" class="error-message">{{ error }}</Message>

    <div v-if="loading" class="loading-container">
      <ProgressSpinner strokeWidth="3" />
    </div>

    <div v-else class="settings-layout">
      <!-- Form Section -->
      <div class="fc-card settings-form">
        <h3 class="section-title">Branding</h3>

        <div class="field">
          <label for="brandName">Brand Name</label>
          <InputText id="brandName" v-model="theme.brandName" class="w-full" />
          <small class="field-help">Displayed as the main heading on the login page</small>
        </div>

        <div class="field">
          <label for="brandSubtitle">Brand Subtitle</label>
          <InputText id="brandSubtitle" v-model="theme.brandSubtitle" class="w-full" />
          <small class="field-help">Displayed below the brand name</small>
        </div>

        <div class="field">
          <label for="footerText">Footer Text</label>
          <InputText id="footerText" v-model="theme.footerText" class="w-full" />
          <small class="field-help">Displayed at the bottom of the login form</small>
        </div>

        <h3 class="section-title">Logo</h3>

        <div class="field">
          <label for="logoUrl">Logo URL</label>
          <InputText
            id="logoUrl"
            v-model="theme.logoUrl"
            class="w-full"
            placeholder="https://example.com/logo.png"
          />
          <small class="field-help">URL to an image file (PNG, SVG, etc.)</small>
        </div>

        <div class="field">
          <label for="logoSvg">Logo SVG (inline)</label>
          <Textarea
            id="logoSvg"
            v-model="theme.logoSvg"
            class="w-full"
            rows="4"
            placeholder="<svg>...</svg>"
          />
          <small class="field-help"
            >Paste inline SVG markup. Takes precedence over Logo URL if both are set.</small
          >
        </div>

        <div class="field">
          <label for="logoHeight">Logo Height (px)</label>
          <InputText
            id="logoHeight"
            :model-value="theme.logoHeight != null ? String(theme.logoHeight) : ''"
            @update:model-value="
              (v: string | undefined) => (theme.logoHeight = v ? Number(v) : undefined)
            "
            type="number"
            class="w-small"
            min="20"
            max="120"
          />
          <small class="field-help">Height of the logo in pixels (default: 40, max: 120)</small>
        </div>

        <h3 class="section-title">Colors</h3>

        <div class="color-fields">
          <div class="field color-field">
            <label>Primary Color</label>
            <div class="color-input">
              <ColorPicker v-model="primaryColorPicker" @update:modelValue="onPrimaryColorChange" />
              <InputText v-model="theme.primaryColor" class="color-text" />
            </div>
            <small class="field-help">Main brand color for headings</small>
          </div>

          <div class="field color-field">
            <label>Accent Color</label>
            <div class="color-input">
              <ColorPicker v-model="accentColorPicker" @update:modelValue="onAccentColorChange" />
              <InputText v-model="theme.accentColor" class="color-text" />
            </div>
            <small class="field-help">Button and link color</small>
          </div>

          <div class="field color-field">
            <label>Background Color</label>
            <div class="color-input">
              <ColorPicker
                v-model="backgroundColorPicker"
                @update:modelValue="onBackgroundColorChange"
              />
              <InputText v-model="theme.backgroundColor" class="color-text" />
            </div>
            <small class="field-help">Page background color</small>
          </div>
        </div>

        <div class="field">
          <label for="backgroundGradient">Background Gradient</label>
          <InputText id="backgroundGradient" v-model="theme.backgroundGradient" class="w-full" />
          <small class="field-help">CSS gradient (overrides background color if set)</small>
        </div>

        <h3 class="section-title">Advanced</h3>

        <div class="field">
          <label for="customCss">Custom CSS</label>
          <Textarea
            id="customCss"
            v-model="theme.customCss"
            class="w-full"
            rows="4"
            placeholder=".login-card { ... }"
          />
          <small class="field-help">Additional CSS rules to inject on the login page</small>
        </div>

        <div class="form-actions">
          <Button label="Reset to Defaults" text @click="resetToDefaults" />
          <Button label="Save Changes" icon="pi pi-check" @click="saveTheme" :loading="saving" />
        </div>
      </div>

      <!-- Preview Section -->
      <div class="preview-section">
        <h3 class="preview-title">Preview</h3>
        <div class="preview-container" :style="{ background: previewBackground }">
          <div class="preview-content">
            <!-- Logo preview -->
            <img
              v-if="theme.logoUrl && !theme.logoSvg"
              :src="theme.logoUrl"
              class="preview-logo-img"
              alt="Logo"
            />
            <div v-else-if="theme.logoSvg" class="preview-logo-svg" v-html="theme.logoSvg" />
            <div v-else class="preview-logo-default">
              <svg fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="1.5"
                  d="M13 10V3L4 14h7v7l9-11h-7z"
                />
              </svg>
            </div>

            <h1 class="preview-brand" :style="{ color: 'white' }">{{ theme.brandName }}</h1>
            <p class="preview-subtitle">{{ theme.brandSubtitle }}</p>

            <div class="preview-card">
              <h2 class="preview-card-title">Sign in</h2>
              <div class="preview-input"></div>
              <button class="preview-button" :style="{ backgroundColor: theme.accentColor }">
                Continue
              </button>
            </div>

            <p class="preview-footer">{{ theme.footerText }}</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.loading-container {
  display: flex;
  justify-content: center;
  padding: 60px;
}

.error-message {
  margin-bottom: 16px;
}

.settings-layout {
  display: grid;
  grid-template-columns: 1fr 400px;
  gap: 24px;
}

@media (max-width: 1200px) {
  .settings-layout {
    grid-template-columns: 1fr;
  }
}

.settings-form {
  padding: 24px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #1e293b;
  margin: 24px 0 16px;
  padding-bottom: 8px;
  border-bottom: 1px solid #e2e8f0;
}

.section-title:first-child {
  margin-top: 0;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}

.field label {
  font-weight: 500;
  color: #334155;
  font-size: 14px;
}

.field-help {
  color: #94a3b8;
  font-size: 12px;
}

.w-full {
  width: 100%;
}

.w-small {
  width: 120px;
}

.color-fields {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

@media (max-width: 800px) {
  .color-fields {
    grid-template-columns: 1fr;
  }
}

.color-field {
  margin-bottom: 0;
}

.color-input {
  display: flex;
  align-items: center;
  gap: 8px;
}

.color-text {
  width: 100px;
  font-family: monospace;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 32px;
  padding-top: 24px;
  border-top: 1px solid #e2e8f0;
}

/* Preview styles */
.preview-section {
  position: sticky;
  top: 24px;
}

.preview-title {
  font-size: 14px;
  font-weight: 600;
  color: #64748b;
  margin: 0 0 12px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.preview-container {
  border-radius: 12px;
  padding: 32px 24px;
  min-height: 500px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
}

.preview-content {
  text-align: center;
  width: 100%;
  max-width: 280px;
}

.preview-logo-img {
  max-width: 120px;
  max-height: 48px;
  object-fit: contain;
  margin-bottom: 12px;
}

.preview-logo-svg {
  margin-bottom: 12px;
}

.preview-logo-svg :deep(svg) {
  max-width: 120px;
  max-height: 48px;
}

.preview-logo-default {
  width: 48px;
  height: 48px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 12px;
}

.preview-logo-default svg {
  width: 28px;
  height: 28px;
  color: white;
}

.preview-brand {
  font-size: 22px;
  font-weight: 700;
  margin: 0;
}

.preview-subtitle {
  color: #9fb3c8;
  font-size: 13px;
  margin: 4px 0 20px;
}

.preview-card {
  background: white;
  border-radius: 10px;
  padding: 24px 20px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.2);
}

.preview-card-title {
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
  margin: 0 0 16px;
  text-align: left;
}

.preview-input {
  height: 36px;
  background: #f1f5f9;
  border-radius: 6px;
  margin-bottom: 12px;
}

.preview-button {
  width: 100%;
  height: 36px;
  border: none;
  border-radius: 6px;
  color: white;
  font-weight: 500;
  font-size: 13px;
  cursor: default;
}

.preview-footer {
  color: #627d98;
  font-size: 11px;
  margin: 16px 0 0;
}
</style>
