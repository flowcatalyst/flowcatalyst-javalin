/**
 * Layout props for the single global <ConfirmDialog /> (mounted in App.vue).
 *
 * PrimeVue's Dialog has no default width, so a long confirm message sizes the
 * dialog to its text and it renders as a viewport-wide bar. Cap it at a compact
 * modal width; below 600px it fills the viewport minus a 1rem margin each side.
 */
export const CONFIRM_DIALOG_PROPS = {
	style: { width: "32rem", maxWidth: "calc(100vw - 2rem)" },
	breakpoints: { "600px": "calc(100vw - 2rem)" },
} as const;
