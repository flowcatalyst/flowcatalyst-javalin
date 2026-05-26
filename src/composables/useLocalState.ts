import { ref, watch, type Ref } from "vue";

/**
 * A reactive ref backed by localStorage.
 *
 * Reads the initial value from localStorage (falling back to `defaultValue`),
 * and writes back on every change. That's it.
 *
 * Usage:
 * ```ts
 * const sidebarCollapsed = useLocalState('sidebar-collapsed', false)
 * const selectedClientId = useLocalState<string | null>('selected-client', null)
 * ```
 */
export function useLocalState<T>(key: string, defaultValue: T): Ref<T> {
	const storageKey = `fc:${key}`;

	let initial = defaultValue;
	try {
		const stored = localStorage.getItem(storageKey);
		if (stored !== null) {
			initial = JSON.parse(stored) as T;
		}
	} catch {
		// Corrupted value — fall back to default
	}

	const state = ref(initial) as Ref<T>;

	watch(
		state,
		(newVal) => {
			try {
				if (newVal === null || newVal === undefined) {
					localStorage.removeItem(storageKey);
				} else {
					localStorage.setItem(storageKey, JSON.stringify(newVal));
				}
			} catch {
				// localStorage full or unavailable — silently ignore
			}
		},
		{ deep: true },
	);

	return state;
}
