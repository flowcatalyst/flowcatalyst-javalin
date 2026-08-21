<script setup lang="ts">
import { ref, computed, watch, nextTick } from "vue";
import { useRoute, useRouter } from "vue-router";
import { marked } from "marked";
import DOMPurify from "dompurify";
import { docsApi, type DocSummary } from "@/api/docs";
import { getErrorMessage } from "@/utils/errors";

const route = useRoute();
const router = useRouter();

const docs = ref<DocSummary[]>([]);
const listLoading = ref(true);
const listError = ref<string | null>(null);
const filter = ref("");

const docLoading = ref(false);
const docError = ref<string | null>(null);
const docTitle = ref("");
const docHtml = ref("");
const contentEl = ref<HTMLElement | null>(null);

const activeSlug = computed(() => (route.params["slug"] as string) || null);

const filteredDocs = computed(() => {
	const q = filter.value.trim().toLowerCase();
	if (!q) return docs.value;
	return docs.value.filter(
		(d) => d.title.toLowerCase().includes(q) || d.slug.toLowerCase().includes(q),
	);
});

async function loadList() {
	listLoading.value = true;
	listError.value = null;
	try {
		const res = await docsApi.list();
		docs.value = res.docs;
		// Land on the first doc when none is selected.
		if (!activeSlug.value && res.docs.length > 0 && res.docs[0]) {
			void router.replace(`/platform/docs/${res.docs[0].slug}`);
		}
	} catch (e) {
		listError.value = getErrorMessage(e, "Failed to load documentation");
	} finally {
		listLoading.value = false;
	}
}

async function loadDoc(slug: string) {
	docLoading.value = true;
	docError.value = null;
	docHtml.value = "";
	try {
		const doc = await docsApi.get(slug);
		docTitle.value = doc.title;
		// Render markdown → sanitize → inject; mermaid fences are upgraded to
		// SVG afterwards (the sanitizer would mangle inline SVG, so diagrams
		// are rendered post-sanitize from the fence text).
		const raw = marked.parse(doc.content, { async: false }) as string;
		docHtml.value = DOMPurify.sanitize(raw);
		await nextTick();
		await renderMermaid();
	} catch (e) {
		docError.value = getErrorMessage(e, "Failed to load document");
	} finally {
		docLoading.value = false;
	}
}

// Upgrade ```mermaid fences (rendered as <pre><code class="language-mermaid">)
// into SVG diagrams. Mermaid is lazy-loaded so the docs page costs nothing
// unless a diagram is actually on screen (same pattern as the process pages).
async function renderMermaid() {
	const host = contentEl.value;
	if (!host) return;
	const fences = host.querySelectorAll("pre code.language-mermaid");
	if (fences.length === 0) return;
	const mermaid = (await import("mermaid")).default;
	mermaid.initialize({ startOnLoad: false, securityLevel: "strict", theme: "neutral" });
	let i = 0;
	for (const fence of Array.from(fences)) {
		const pre = fence.closest("pre");
		if (!pre?.parentElement) continue;
		const code = fence.textContent ?? "";
		try {
			const { svg } = await mermaid.render(`doc-diagram-${i++}`, code);
			const wrap = document.createElement("div");
			wrap.className = "doc-diagram";
			wrap.innerHTML = svg;
			pre.replaceWith(wrap);
		} catch {
			// Leave the fence as a code block if it doesn't parse.
		}
	}
}

// Cross-doc links: a markdown link to `some-doc.md` (with or without a
// docs/ prefix) navigates in-app instead of 404ing against the SPA.
function onContentClick(event: MouseEvent) {
	const anchor = (event.target as HTMLElement).closest("a");
	if (!anchor) return;
	const href = anchor.getAttribute("href") ?? "";
	const match = href.match(/^(?:\.\/)?(?:docs\/)?([a-zA-Z0-9_-]+)\.md(?:#.*)?$/);
	if (match?.[1]) {
		event.preventDefault();
		void router.push(`/platform/docs/${match[1]}`);
		return;
	}
	// External links leave the app in a new tab.
	if (/^https?:\/\//.test(href)) {
		event.preventDefault();
		window.open(href, "_blank", "noopener");
	}
}

watch(activeSlug, (slug) => {
	if (slug) void loadDoc(slug);
});

void loadList();
if (activeSlug.value) void loadDoc(activeSlug.value);
</script>

<template>
  <div class="page-container docs-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">Documentation</h1>
        <p class="page-subtitle">
          Platform reference docs, embedded with this build — always in step with the running version.
        </p>
      </div>
    </header>

    <Message v-if="listError" severity="error" class="error-message">{{ listError }}</Message>

    <div v-else class="docs-layout">
      <aside class="docs-nav">
        <InputText v-model="filter" placeholder="Filter docs..." class="docs-filter" />
        <div v-if="listLoading" class="docs-nav-loading">
          <i class="pi pi-spinner pi-spin"></i>
        </div>
        <nav v-else class="docs-list">
          <router-link
            v-for="d in filteredDocs"
            :key="d.slug"
            :to="`/platform/docs/${d.slug}`"
            class="docs-link"
            :class="{ active: d.slug === activeSlug }"
          >
            {{ d.title }}
          </router-link>
        </nav>
      </aside>

      <main class="docs-content-pane">
        <div v-if="docLoading" class="docs-content-loading">
          <i class="pi pi-spinner pi-spin"></i> Loading…
        </div>
        <Message v-else-if="docError" severity="error">{{ docError }}</Message>
        <!-- eslint-disable-next-line vue/no-v-html — sanitized above -->
        <article
          v-else
          ref="contentEl"
          class="docs-content"
          @click="onContentClick"
          v-html="docHtml"
        ></article>
      </main>
    </div>
  </div>
</template>

<style scoped>
.docs-layout {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 24px;
  align-items: start;
}

.docs-nav {
  position: sticky;
  top: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.docs-filter {
  width: 100%;
}

.docs-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.docs-link {
  padding: 7px 10px;
  border-radius: 6px;
  color: var(--text-color, #334155);
  text-decoration: none;
  font-size: 13.5px;
  line-height: 1.35;
}

.docs-link:hover {
  background: var(--surface-hover, #f1f5f9);
}

.docs-link.active {
  background: var(--highlight-bg, #eef2ff);
  color: var(--primary-color, #4f46e5);
  font-weight: 600;
}

.docs-nav-loading,
.docs-content-loading {
  color: var(--text-color-secondary, #64748b);
  padding: 16px 0;
}

.docs-content-pane {
  min-width: 0;
  background: var(--surface-card, #ffffff);
  border: 1px solid var(--surface-border, #e2e8f0);
  border-radius: 8px;
  padding: 28px 32px;
}

/* Rendered markdown */
.docs-content {
  max-width: 76ch;
  line-height: 1.65;
  font-size: 14.5px;
  color: var(--text-color, #1e293b);
}

.docs-content :deep(h1) {
  font-size: 1.7rem;
  margin: 0 0 0.6em;
}

.docs-content :deep(h2) {
  font-size: 1.25rem;
  margin: 1.6em 0 0.5em;
  padding-top: 0.6em;
  border-top: 1px solid var(--surface-border, #e2e8f0);
}

.docs-content :deep(h3) {
  font-size: 1.05rem;
  margin: 1.3em 0 0.4em;
}

.docs-content :deep(p) {
  margin: 0.7em 0;
}

.docs-content :deep(code) {
  background: var(--surface-ground, #f1f5f9);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 0.9em;
}

.docs-content :deep(pre) {
  background: var(--surface-ground, #f1f5f9);
  border: 1px solid var(--surface-border, #e2e8f0);
  border-radius: 6px;
  padding: 12px 14px;
  overflow-x: auto;
}

.docs-content :deep(pre code) {
  background: none;
  padding: 0;
}

.docs-content :deep(table) {
  border-collapse: collapse;
  margin: 1em 0;
  display: block;
  overflow-x: auto;
}

.docs-content :deep(th),
.docs-content :deep(td) {
  border: 1px solid var(--surface-border, #e2e8f0);
  padding: 6px 10px;
  text-align: left;
  vertical-align: top;
}

.docs-content :deep(th) {
  background: var(--surface-ground, #f8fafc);
}

.docs-content :deep(blockquote) {
  margin: 1em 0;
  padding: 4px 14px;
  border-left: 3px solid var(--primary-color, #4f46e5);
  color: var(--text-color-secondary, #475569);
}

.docs-content :deep(.doc-diagram) {
  margin: 1.2em 0;
  overflow-x: auto;
}

.docs-content :deep(.doc-diagram svg) {
  max-width: 100%;
  height: auto;
}

@media (max-width: 900px) {
  .docs-layout {
    grid-template-columns: 1fr;
  }

  .docs-nav {
    position: static;
  }
}
</style>
