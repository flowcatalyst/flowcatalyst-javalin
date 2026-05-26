<script setup lang="ts">
import { ref, onMounted, onUnmounted } from "vue";
import { useRouter } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import { logout, checkEmailDomain } from "@/api/auth";

const authStore = useAuthStore();
const router = useRouter();

const menuOpen = ref(false);
const showIdpNotice = ref(false);
const menuRef = ref<HTMLElement | null>(null);

function toggleMenu() {
	menuOpen.value = !menuOpen.value;
}

function closeMenu() {
	menuOpen.value = false;
}

async function handleResetPassword() {
	closeMenu();

	const email = authStore.user?.email;
	if (!email) return;

	try {
		const domainCheck = await checkEmailDomain(email);
		if (domainCheck.authMethod === "external") {
			showIdpNotice.value = true;
		} else {
			await router.push("/auth/reset-password");
		}
	} catch {
		await router.push("/auth/reset-password");
	}
}

function closeIdpNotice() {
	showIdpNotice.value = false;
}

async function handleLogout() {
	closeMenu();
	await logout();
}

function handleClickOutside(event: MouseEvent) {
	if (menuRef.value && !menuRef.value.contains(event.target as Node)) {
		menuOpen.value = false;
	}
}

onMounted(() => {
	document.addEventListener("click", handleClickOutside);
});

onUnmounted(() => {
	document.removeEventListener("click", handleClickOutside);
});
</script>

<template>
  <div ref="menuRef" class="user-menu-container">
    <button class="user-menu-trigger" @click="toggleMenu">
      <div class="user-avatar">
        {{ authStore.userInitials }}
      </div>
      <div class="user-info">
        <span class="user-name">{{ authStore.displayName }}</span>
        <span class="user-email">{{ authStore.user?.email }}</span>
      </div>
      <i class="pi pi-chevron-down" :class="{ rotated: menuOpen }"></i>
    </button>

    <div v-if="menuOpen" class="user-menu-dropdown">
      <div class="menu-header">
        <div class="user-avatar large">
          {{ authStore.userInitials }}
        </div>
        <div class="user-details">
          <span class="name">{{ authStore.displayName }}</span>
          <span class="email">{{ authStore.user?.email }}</span>
          <span v-if="authStore.isPlatformAdmin" class="badge admin">Platform Admin</span>
        </div>
      </div>

      <div class="menu-divider"></div>

      <div class="menu-items">
        <RouterLink to="/profile" class="menu-item" @click="closeMenu">
          <i class="pi pi-user"></i>
          <span>Profile</span>
        </RouterLink>
        <button class="menu-item" @click="handleResetPassword">
          <i class="pi pi-key"></i>
          <span>Reset Password</span>
        </button>
      </div>

      <div class="menu-divider"></div>

      <div class="menu-items">
        <button class="menu-item danger" @click="handleLogout">
          <i class="pi pi-sign-out"></i>
          <span>Sign Out</span>
        </button>
      </div>
    </div>

    <!-- IDP Notice Dialog -->
    <div v-if="showIdpNotice" class="idp-dialog-overlay" @click="closeIdpNotice">
      <div class="idp-dialog" @click.stop>
        <div class="idp-dialog-header">
          <i class="pi pi-info-circle"></i>
          <h3>External Identity Provider</h3>
        </div>
        <div class="idp-dialog-content">
          <p>Your account is managed by an external identity provider.</p>
          <p>To reset your password, please visit your organization's identity provider portal.</p>
        </div>
        <div class="idp-dialog-actions">
          <button class="btn-secondary" @click="closeIdpNotice">Close</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.user-menu-container {
  position: relative;
}

.user-menu-trigger {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 12px;
  background: none;
  border: 1px solid transparent;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.user-menu-trigger:hover {
  background: #f8fafc;
  border-color: #e2e8f0;
}

.user-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: linear-gradient(135deg, #0967d2 0%, #47a3f3 100%);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  font-size: 14px;
  flex-shrink: 0;
}

.user-avatar.large {
  width: 48px;
  height: 48px;
  font-size: 18px;
}

.user-info {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  text-align: left;
}

.user-name {
  font-weight: 500;
  color: #1e293b;
  font-size: 14px;
  line-height: 1.3;
}

.user-email {
  font-size: 12px;
  color: #64748b;
  line-height: 1.3;
}

.user-menu-trigger i {
  color: #64748b;
  font-size: 12px;
  transition: transform 0.2s ease;
}

.user-menu-trigger i.rotated {
  transform: rotate(180deg);
}

.user-menu-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  min-width: 280px;
  background: white;
  border-radius: 12px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.15);
  border: 1px solid #e2e8f0;
  z-index: 1000;
  animation: dropdownFadeIn 0.15s ease;
}

@keyframes dropdownFadeIn {
  from {
    opacity: 0;
    transform: translateY(-8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.menu-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
}

.user-details {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.user-details .name {
  font-weight: 600;
  color: #1e293b;
  font-size: 15px;
}

.user-details .email {
  font-size: 13px;
  color: #64748b;
}

.badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
  margin-top: 4px;
  width: fit-content;
}

.badge.admin {
  background: #dbeafe;
  color: #1d4ed8;
}

.menu-divider {
  height: 1px;
  background: #e2e8f0;
  margin: 0;
}

.menu-items {
  padding: 8px;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 10px 12px;
  background: none;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  color: #475569;
  font-size: 14px;
  text-decoration: none;
  transition: all 0.15s ease;
  text-align: left;
}

.menu-item:hover {
  background: #f1f5f9;
  color: #1e293b;
}

.menu-item i {
  font-size: 16px;
  width: 20px;
  text-align: center;
}

.menu-item.danger {
  color: #dc2626;
}

.menu-item.danger:hover {
  background: #fef2f2;
  color: #b91c1c;
}

/* IDP Notice Dialog */
.idp-dialog-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  animation: fadeIn 0.2s ease;
}

@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.idp-dialog {
  background: white;
  border-radius: 12px;
  width: 100%;
  max-width: 400px;
  margin: 16px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
  animation: slideUp 0.2s ease;
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.idp-dialog-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 24px;
  border-bottom: 1px solid #e2e8f0;
}

.idp-dialog-header i {
  font-size: 24px;
  color: #0967d2;
}

.idp-dialog-header h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #1e293b;
}

.idp-dialog-content {
  padding: 20px 24px;
}

.idp-dialog-content p {
  margin: 0 0 12px;
  color: #475569;
  line-height: 1.5;
}

.idp-dialog-content p:last-child {
  margin-bottom: 0;
}

.idp-dialog-actions {
  display: flex;
  justify-content: flex-end;
  padding: 16px 24px;
  border-top: 1px solid #e2e8f0;
}

.btn-secondary {
  padding: 10px 20px;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  color: #475569;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-secondary:hover {
  background: #e2e8f0;
  color: #1e293b;
}

@media (max-width: 640px) {
  .user-info {
    display: none;
  }

  .user-menu-trigger {
    padding: 6px;
  }

  .user-menu-trigger i.pi-chevron-down {
    display: none;
  }

  .user-menu-dropdown {
    right: -16px;
  }
}
</style>
