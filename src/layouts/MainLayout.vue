<script setup lang="ts">
import { useLocalState } from "@/composables/useLocalState";

const sidebarCollapsed = useLocalState("sidebar-collapsed", false);

function toggleSidebar() {
	sidebarCollapsed.value = !sidebarCollapsed.value;
}
</script>

<template>
  <div class="layout-container">
    <AppSidebar :collapsed="sidebarCollapsed" @toggle-collapse="toggleSidebar" />
    <div class="layout-main" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
      <AppHeader :sidebar-collapsed="sidebarCollapsed" @toggle-sidebar="toggleSidebar" />
      <main class="layout-content">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout-container {
  display: flex;
  min-height: 100vh;
  background-color: #f8fafc;
}

.layout-main {
  flex: 1;
  margin-left: 260px;
  transition: margin-left 0.3s ease;
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.layout-main.sidebar-collapsed {
  margin-left: 72px;
}

.layout-content {
  flex: 1;
  padding: 16px;
  margin-top: 64px;
}

@media (max-width: 768px) {
  .layout-main {
    margin-left: 0;
  }

  .layout-main.sidebar-collapsed {
    margin-left: 0;
  }
}
</style>
