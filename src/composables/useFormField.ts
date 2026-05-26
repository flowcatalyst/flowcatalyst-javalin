import { useField } from "vee-validate";
import { computed } from "vue";

/**
 * Wrapper around vee-validate's useField that provides
 * PrimeVue-compatible error state.
 */
export function useFormField<T = string>(name: string) {
	const { value, errorMessage, handleBlur, handleChange, meta } =
		useField<T>(name);

	// PrimeVue uses 'invalid' prop for error styling
	const invalid = computed(() => meta.touched && !!errorMessage.value);

	return {
		value,
		errorMessage,
		handleBlur,
		handleChange,
		meta,
		invalid,
	};
}
