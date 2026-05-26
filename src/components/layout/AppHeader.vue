<script setup lang="ts">
defineProps<{
	sidebarCollapsed: boolean;
}>();

const emit = defineEmits<{
	toggleSidebar: [];
}>();
</script>

<template>
  <header class="header" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
    <div class="header-left">
      <button class="menu-toggle" @click="emit('toggleSidebar')">
        <i class="pi pi-bars"></i>
      </button>
      <div class="breadcrumb">
        <!-- Breadcrumb can be implemented later -->
      </div>
    </div>

    <div class="header-right">
      <UserMenu />
    </div>
  </header>
</template>

<style scoped>
.header {
  position: fixed;
  top: 0;
  right: 0;
  left: 260px;
  height: 64px;
  background: #ffffff;
  border-bottom: 1px solid #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  transition: left 0.3s ease;
  z-index: 999;
}

.header.sidebar-collapsed {
  left: 72px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.menu-toggle {
  display: none;
  background: none;
  border: none;
  padding: 8px;
  cursor: pointer;
  color: #64748b;
  border-radius: 6px;
  transition: all 0.2s ease;
}

.menu-toggle:hover {
  background: #f1f5f9;
  color: #334155;
}

.menu-toggle i {
  font-size: 20px;
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #64748b;
  font-size: 14px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

@media (max-width: 768px) {
  .header {
    left: 0;
  }

  .header.sidebar-collapsed {
    left: 0;
  }

  .menu-toggle {
    display: flex;
  }
}
</style>
