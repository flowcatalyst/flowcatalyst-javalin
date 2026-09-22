<script setup lang="ts">
// A minimal stand-in for a page not yet built. Registers a real route (so
// navigation + permission gating are complete) without any content beyond
// the title. Used by H1 for /function-domains and /function-policies —
// H3 replaces both with the real pages (docs/spec/function-ui.md §7).
defineProps<{
	title: string;
}>();
</script>

<template>
  <div class="page-container">
    <header class="page-header">
      <h1 class="page-title">{{ title }}</h1>
    </header>
  </div>
</template>
