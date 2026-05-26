import { bffFetch } from "./client";

export interface FilterOption {
	value: string;
	label: string;
}

interface ClientFilterOptions {
	clients: FilterOption[];
}

export const filterOptionsApi = {
	clients(): Promise<ClientFilterOptions> {
		return bffFetch("/filter-options/clients");
	},
};
