<script setup lang="ts">
import { computed } from "vue";
import { useRouter } from "vue-router";
import { usePermissionsStore } from "@/stores/permissions";
import { logout } from "@/api/auth";

const router = useRouter();
const permissionsStore = usePermissionsStore();

const visible = computed({
	get: () => permissionsStore.showPermissionModal,
	set: (value) => {
		if (!value) {
			permissionsStore.hidePermissionDenied();
		}
	},
});

const event = computed(() => permissionsStore.permissionDenied);

const isSessionExpired = computed(() => {
	return (
		event.value?.message?.toLowerCase().includes("session") ||
		event.value?.message?.toLowerCase().includes("expired") ||
		event.value?.message?.toLowerCase().includes("authenticated")
	);
});

const title = computed(() => {
	if (isSessionExpired.value) {
		return "Session Expired";
	}
	return "Permission Denied";
});

const icon = computed(() => {
	if (isSessionExpired.value) {
		return "pi pi-clock";
	}
	return "pi pi-lock";
});

function goBack() {
	permissionsStore.hidePermissionDenied();
	router.back();
}

function goHome() {
	permissionsStore.hidePermissionDenied();
	router.push("/dashboard");
}

async function handleLogout() {
	permissionsStore.hidePermissionDenied();
	await logout();
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="title"
    modal
    :closable="!isSessionExpired"
    :closeOnEscape="!isSessionExpired"
    :style="{ width: '420px' }"
    class="permission-dialog"
  >
    <div class="dialog-content">
      <div class="icon-wrapper" :class="{ expired: isSessionExpired }">
        <i :class="icon"></i>
      </div>

      <p class="message">{{ event?.message }}</p>

      <p v-if="event?.requiredPermission" class="permission-info">
        Required permission: <code>{{ event.requiredPermission }}</code>
      </p>

      <p v-if="!isSessionExpired" class="help-text">
        Contact your administrator if you believe you should have access to this resource.
      </p>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <template v-if="isSessionExpired">
          <Button label="Log In Again" icon="pi pi-sign-in" @click="handleLogout" />
        </template>
        <template v-else>
          <Button
            label="Go Back"
            icon="pi pi-arrow-left"
            severity="secondary"
            outlined
            @click="goBack"
          />
          <Button label="Go to Dashboard" icon="pi pi-home" @click="goHome" />
        </template>
      </div>
    </template>
  </Dialog>
</template>

<style scoped>
.dialog-content {
  text-align: center;
  padding: 8px 0;
}

.icon-wrapper {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: #fef2f2;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 16px;
}

.icon-wrapper i {
  font-size: 28px;
  color: #dc2626;
}

.icon-wrapper.expired {
  background: #fefce8;
}

.icon-wrapper.expired i {
  color: #ca8a04;
}

.message {
  font-size: 15px;
  color: #374151;
  margin: 0 0 12px;
  line-height: 1.5;
}

.permission-info {
  font-size: 13px;
  color: #6b7280;
  margin: 0 0 12px;
}

.permission-info code {
  background: #f3f4f6;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
}

.help-text {
  font-size: 13px;
  color: #9ca3af;
  margin: 0;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
