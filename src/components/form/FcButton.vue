<script setup lang="ts">
withDefaults(
	defineProps<{
		label?: string;
		icon?: string;
		iconPos?: "left" | "right" | "top" | "bottom";
		loading?: boolean;
		disabled?: boolean;
		severity?:
			| "primary"
			| "secondary"
			| "success"
			| "danger"
			| "info"
			| "help"
			| "contrast"
			| "warn";
		outlined?: boolean;
		text?: boolean;
		rounded?: boolean;
		size?: "small" | "large";
		type?: "button" | "submit" | "reset";
	}>(),
	{
		label: "",
		icon: "",
		iconPos: "left",
		loading: false,
		disabled: false,
		severity: "primary",
		outlined: false,
		text: false,
		rounded: false,
		type: "button",
	},
);

const emit = defineEmits<{
	click: [event: MouseEvent];
}>();
</script>

<template>
  <Button
    :label="label"
    :icon="icon"
    :iconPos="iconPos"
    :loading="loading"
    :disabled="disabled || loading"
    :severity="severity"
    :outlined="outlined"
    :text="text"
    :rounded="rounded"
    :size="size"
    :type="type"
    @click="emit('click', $event)"
  />
</template>
