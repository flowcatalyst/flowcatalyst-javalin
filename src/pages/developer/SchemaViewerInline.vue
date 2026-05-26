<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { toast } from "@/utils/errorBus";
import {
  generateExample,
  generateTypeScriptInterface,
  generatePhpDto,
  generatePythonDataclass,
  generateJavaRecord,
} from "@flowcatalyst/schema-codegen";
import {
  highlightJson,
  highlightTypeScript,
  highlightPhp,
  highlightPython,
  highlightJava,
} from "@/utils/schema-highlight";
import type { DeveloperSpecVersionSummary } from "@/api/developer";

/**
 * Inline (non-dialog) schema + codegen viewer for the Developer portal's
 * master-detail layout. Shares the same look + codegen path as
 * `event-types/SchemaViewerDialog.vue`, lifted out of the modal wrapper.
 */
const props = defineProps<{
  specVersion: DeveloperSpecVersionSummary | null;
  eventCode: string;
}>();

type Tab = "schema" | "example" | "typescript" | "php" | "python" | "java";
const activeTab = ref<Tab>("schema");
const tabLabels: Record<Tab, string> = {
  schema: "Schema",
  example: "Example",
  typescript: "TypeScript",
  php: "PHP",
  python: "Python",
  java: "Java",
};

const parsedSchema = computed(() => {
  if (!props.specVersion?.schema) return null;
  try {
    return JSON.parse(props.specVersion.schema);
  } catch {
    return null;
  }
});

const formattedSchema = computed(() => {
  if (!parsedSchema.value) return props.specVersion?.schema ?? "";
  return JSON.stringify(parsedSchema.value, null, 2);
});

const formattedExample = computed(() => {
  if (!parsedSchema.value) return "// Unable to generate example";
  try {
    return JSON.stringify(generateExample(parsedSchema.value), null, 2);
  } catch {
    return "// Unable to generate example";
  }
});

const generatedTypeScript = computed(() => {
  if (!parsedSchema.value) return "// No schema available";
  try {
    return generateTypeScriptInterface(parsedSchema.value, props.eventCode);
  } catch {
    return "// Unable to generate TypeScript interface";
  }
});

const generatedPhp = computed(() => {
  if (!parsedSchema.value) return "// No schema available";
  try {
    return generatePhpDto(parsedSchema.value, props.eventCode);
  } catch {
    return "// Unable to generate PHP DTO";
  }
});

const generatedPython = computed(() => {
  if (!parsedSchema.value) return "# No schema available";
  try {
    return generatePythonDataclass(parsedSchema.value, props.eventCode);
  } catch {
    return "# Unable to generate Python dataclass";
  }
});

const generatedJava = computed(() => {
  if (!parsedSchema.value) return "// No schema available";
  try {
    return generateJavaRecord(parsedSchema.value, props.eventCode);
  } catch {
    return "// Unable to generate Java record";
  }
});

const displayContent = computed(() => {
  switch (activeTab.value) {
    case "schema":
      return formattedSchema.value;
    case "example":
      return formattedExample.value;
    case "typescript":
      return generatedTypeScript.value;
    case "php":
      return generatedPhp.value;
    case "python":
      return generatedPython.value;
    case "java":
      return generatedJava.value;
  }
});

const highlightedContent = computed(() => {
  switch (activeTab.value) {
    case "schema":
    case "example":
      return highlightJson(displayContent.value);
    case "typescript":
      return highlightTypeScript(displayContent.value);
    case "php":
      return highlightPhp(displayContent.value);
    case "python":
      return highlightPython(displayContent.value);
    case "java":
      return highlightJava(displayContent.value);
  }
});

// Reset to Schema tab whenever the selected event type changes.
watch(
  () => props.specVersion?.id,
  () => {
    activeTab.value = "schema";
  },
);

async function copyToClipboard() {
  try {
    await navigator.clipboard.writeText(displayContent.value);
    toast.success("Copied", `${tabLabels[activeTab.value]} copied to clipboard`);
  } catch {
    // navigator.clipboard rejects in insecure contexts — silent failure is fine.
  }
}
</script>

<template>
  <div class="viewer-inline">
    <div class="viewer-toolbar">
      <div class="tab-group">
        <button
          v-for="tab in (['schema','example','typescript','php','python','java'] as Tab[])"
          :key="tab"
          class="tab-btn"
          :class="{ active: activeTab === tab }"
          @click="activeTab = tab"
        >
          {{ tabLabels[tab] }}
        </button>
      </div>
      <div class="toolbar-actions">
        <Tag
          v-if="specVersion"
          :value="specVersion.status"
          :severity="specVersion.status === 'CURRENT' ? 'success' : specVersion.status === 'FINALISING' ? 'info' : 'warn'"
        />
        <Button
          icon="pi pi-copy"
          text
          rounded
          severity="secondary"
          v-tooltip="'Copy'"
          @click="copyToClipboard"
        />
      </div>
    </div>
    <div class="code-container">
      <pre class="code-block"><code v-html="highlightedContent"></code></pre>
    </div>
  </div>
</template>

<style scoped>
.viewer-inline {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
}

.viewer-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 16px;
  border-bottom: 1px solid var(--surface-border, #e2e8f0);
  background: var(--surface-ground, #f8fafc);
}

.tab-group {
  display: flex;
  gap: 2px;
  background: var(--surface-border, #e2e8f0);
  border-radius: 6px;
  padding: 2px;
  flex-wrap: wrap;
}

.tab-btn {
  padding: 6px 16px;
  border: none;
  background: transparent;
  border-radius: 4px;
  font-size: 13px;
  font-weight: 500;
  color: #64748b;
  cursor: pointer;
  transition: all 0.15s;
}

.tab-btn:hover {
  color: #334155;
}

.tab-btn.active {
  background: white;
  color: #0f172a;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.code-container {
  flex: 1;
  overflow: auto;
  min-height: 0;
}

.code-block {
  margin: 0;
  padding: 16px;
  background: #1e293b;
  color: #e2e8f0;
  font-family: "SF Mono", "Fira Code", "Cascadia Code", monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre;
  overflow-x: auto;
  min-height: 100%;
}

.code-block code {
  background: none;
  padding: 0;
  color: inherit;
}

.code-block :deep(.json-key) { color: #7dd3fc; }
.code-block :deep(.json-str) { color: #86efac; }
.code-block :deep(.json-num) { color: #fde68a; }
.code-block :deep(.json-kw)  { color: #c4b5fd; }
.code-block :deep(.hl-key)     { color: #7dd3fc; }
.code-block :deep(.hl-str)     { color: #86efac; }
.code-block :deep(.hl-num)     { color: #fde68a; }
.code-block :deep(.hl-kw)      { color: #c4b5fd; }
.code-block :deep(.hl-type)    { color: #67e8f9; }
.code-block :deep(.hl-var)     { color: #fca5a5; }
.code-block :deep(.hl-comment) { color: #94a3b8; font-style: italic; }
</style>
