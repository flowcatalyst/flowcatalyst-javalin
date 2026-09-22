// jsdom has no ResizeObserver; PrimeVue's TabList binds one on mount, so any
// component with <Tabs> fails to mount in a test without this. A no-op is
// correct here: nothing in a unit test resizes.
if (typeof globalThis.ResizeObserver === "undefined") {
	class NoopResizeObserver {
		observe() {}
		unobserve() {}
		disconnect() {}
	}
	(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = NoopResizeObserver;
}
