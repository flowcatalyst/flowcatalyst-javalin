<script setup lang="ts">
import { ref, computed } from "vue";
import { toast } from "@/utils/errorBus";
import { getErrorMessage } from "@/utils/errors";
import { usersApi, type User, type MatchEmailsResponse } from "@/api/users";

// Paste a list of email addresses and see which are users. The server parses
// the text and applies the same visibility rule as the user list, so a user
// the admin may not see comes back under "Not found".
const emit = defineEmits<{ (e: "select", user: User): void }>();

const visible = ref(false);
const text = ref("");
const matching = ref(false);
const error = ref("");
const result = ref<MatchEmailsResponse | null>(null);

const summary = computed(() => {
	const r = result.value;
	if (!r) return "";
	return `${r.matches.length} matched, ${r.unmatched.length} not found, ${r.invalid.length} not an email`;
});

function open() {
	text.value = "";
	error.value = "";
	result.value = null;
	visible.value = true;
}

async function match() {
	if (!text.value.trim()) {
		error.value = "Paste at least one email address.";
		return;
	}
	matching.value = true;
	error.value = "";
	try {
		result.value = await usersApi.matchEmails(text.value);
	} catch (e) {
		result.value = null;
		error.value = getErrorMessage(e, "Matching failed.");
	} finally {
		matching.value = false;
	}
}

function select(user: User) {
	visible.value = false;
	emit("select", user);
}

async function copy(list: string[]) {
	try {
		await navigator.clipboard.writeText(list.join("\n"));
		toast.success("Copied", `${list.length} entr${list.length === 1 ? "y" : "ies"}`);
	} catch {
		toast.error("Copy failed", "Select the list and copy it manually.");
	}
}
</script>

<template>
	<Button label="Match emails" icon="pi pi-search" outlined @click="open" />

	<Dialog
		v-model:visible="visible"
		header="Match emails"
		modal
		:style="{ width: '48rem' }"
	>
		<div class="dialog-form">
			<div class="field">
				<label for="match-emails-text">Email addresses</label>
				<Textarea
					id="match-emails-text"
					v-model="text"
					rows="8"
					class="w-full mono"
					spellcheck="false"
				/>
				<small class="hint">
					Paste emails separated by commas, semicolons or new lines.
					'Name &lt;email&gt;' works too.
				</small>
			</div>

			<p v-if="error" class="error-text">{{ error }}</p>

			<div v-if="result" class="results">
				<p class="summary">{{ summary }}</p>

				<DataTable
					v-if="result.matches.length"
					:value="result.matches"
					size="small"
					stripedRows
					rowHover
					class="match-table"
					@row-click="select($event.data)"
				>
					<Column field="name" header="Name" />
					<Column field="email" header="Email" />
					<Column field="scope" header="Scope" />
					<Column header="Active" style="width: 5rem">
						<template #body="{ data }">
							<Tag
								:value="data.active ? 'Active' : 'Inactive'"
								:severity="data.active ? 'success' : 'danger'"
							/>
						</template>
					</Column>
				</DataTable>

				<div v-if="result.unmatched.length" class="list">
					<div class="list-head">
						<strong>Not found</strong>
						<Button
							label="Copy"
							icon="pi pi-copy"
							text
							size="small"
							@click="copy(result.unmatched)"
						/>
					</div>
					<Textarea
						:model-value="result.unmatched.join('\n')"
						readonly
						:rows="Math.min(result.unmatched.length, 8)"
						class="w-full mono"
					/>
				</div>

				<div v-if="result.invalid.length" class="list">
					<div class="list-head">
						<strong>Not an email</strong>
						<Button
							label="Copy"
							icon="pi pi-copy"
							text
							size="small"
							@click="copy(result.invalid)"
						/>
					</div>
					<Textarea
						:model-value="result.invalid.join('\n')"
						readonly
						:rows="Math.min(result.invalid.length, 8)"
						class="w-full mono"
					/>
				</div>
			</div>
		</div>
		<template #footer>
			<Button
				label="Close"
				text
				severity="secondary"
				@click="visible = false"
			/>
			<Button
				label="Match"
				icon="pi pi-search"
				:loading="matching"
				@click="match"
			/>
		</template>
	</Dialog>
</template>

<style scoped>
.dialog-form {
	display: flex;
	flex-direction: column;
	gap: 16px;
}
.field {
	display: flex;
	flex-direction: column;
	gap: 6px;
}
.field label {
	font-size: 13px;
	font-weight: 500;
	color: #475569;
}
.hint {
	color: #64748b;
}
.error-text {
	color: #dc2626;
	font-size: 13px;
	margin: 0;
}
.results {
	display: flex;
	flex-direction: column;
	gap: 12px;
}
.summary {
	font-size: 14px;
	font-weight: 500;
	margin: 0;
}
.match-table :deep(tr) {
	cursor: pointer;
}
.list {
	display: flex;
	flex-direction: column;
	gap: 4px;
}
.list-head {
	display: flex;
	align-items: center;
	justify-content: space-between;
}
.mono {
	font-family: ui-monospace, monospace;
	font-size: 13px;
}
.w-full {
	width: 100%;
}
</style>
