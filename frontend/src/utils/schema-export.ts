/**
 * Schema Export
 *
 * Builds a ZIP file of all CURRENT JSON Schemas from the visible event types
 * and triggers a browser download.
 *
 * File structure inside the ZIP:
 *   schemas/{application}/{subdomain}/{aggregate}/{event}.json
 *   php/{Application}/{Subdomain}/{Aggregate}/{AggregateEventData}.php
 *   typescript/{application}/{subdomain}/{aggregate}/{aggregateEventData}.ts
 *   python/{application}/{subdomain}/{aggregate}/{aggregate_event_data}.py
 *   java/{com}/{app}/events/{subdomain}/{aggregate}/{AggregateEventData}.java
 */

import { zipSync, strToU8 } from "fflate";
import type { EventType } from "@/api/event-types";
import {
	generateTypeScriptInterface,
	generatePhpDto,
	generatePythonDataclass,
	generateJavaRecord,
} from "@flowcatalyst/schema-codegen";

export interface ExportResult {
	exported: number;
	errors: string[];
}

export function exportSchemasAsZip(eventTypes: EventType[]): ExportResult {
	const files: Record<string, Uint8Array> = {};
	const errors: string[] = [];
	let count = 0;

	for (const et of eventTypes) {
		const current = et.specVersions.find((sv) => sv.status === "CURRENT");
		if (!current?.schema) continue;

		const eventCode = `${et.application}:${et.subdomain}:${et.aggregate}:${et.event}`;

		// JSON Schema
		let parsed: Record<string, unknown>;
		try {
			const content = formatSchema(current.schema);
			const schemaPath = `schemas/${et.application}/${et.subdomain}/${et.aggregate}/${et.event}.json`;
			files[schemaPath] = strToU8(content);
			parsed = JSON.parse(current.schema) as Record<string, unknown>;
		} catch {
			errors.push(`${eventCode}: invalid schema JSON`);
			continue;
		}

		// PHP DTO
		try {
			const phpContent = generatePhpDto(parsed, eventCode);
			const phpPath = `php/${pascalCase(et.application)}/${pascalCase(et.subdomain)}/${pascalCase(et.aggregate)}/${pascalCase(et.aggregate)}${pascalCase(et.event)}Data.php`;
			files[phpPath] = strToU8(phpContent);
		} catch {
			errors.push(`${eventCode}: PHP generation failed`);
		}

		// TypeScript interface
		try {
			const tsContent = generateTypeScriptInterface(parsed, eventCode);
			const tsPath = `typescript/${et.application}/${et.subdomain}/${et.aggregate}/${camelCase(et.aggregate)}${pascalCase(et.event)}Data.ts`;
			files[tsPath] = strToU8(tsContent);
		} catch {
			errors.push(`${eventCode}: TypeScript generation failed`);
		}

		// Python dataclass
		try {
			const pyContent = generatePythonDataclass(parsed, eventCode);
			const pyPath = `python/${et.application}/${et.subdomain}/${et.aggregate}/${toSnakeCase(et.aggregate)}_${toSnakeCase(et.event)}_data.py`;
			files[pyPath] = strToU8(pyContent);
		} catch {
			errors.push(`${eventCode}: Python generation failed`);
		}

		// Java record
		try {
			const javaContent = generateJavaRecord(parsed, eventCode);
			const javaPath = `java/com/${et.application.toLowerCase()}/events/${et.subdomain.toLowerCase()}/${et.aggregate.toLowerCase()}/${pascalCase(et.aggregate)}${pascalCase(et.event)}Data.java`;
			files[javaPath] = strToU8(javaContent);
		} catch {
			errors.push(`${eventCode}: Java generation failed`);
		}

		count++;
	}

	if (count === 0) return { exported: 0, errors };

	const zip = zipSync(files);
	const blob = new Blob([zip], { type: "application/zip" });
	downloadBlob(blob, "event-schemas.zip");

	return { exported: count, errors };
}

function formatSchema(schema: string): string {
	try {
		return JSON.stringify(JSON.parse(schema), null, 2);
	} catch {
		return schema;
	}
}

function pascalCase(s: string): string {
	return s
		.split(/[-_]/)
		.map((w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
		.join("");
}

function camelCase(s: string): string {
	const pascal = pascalCase(s);
	return pascal.charAt(0).toLowerCase() + pascal.slice(1);
}

function toSnakeCase(s: string): string {
	return s
		.replace(/([A-Z])/g, "_$1")
		.toLowerCase()
		.replace(/^_/, "")
		.replace(/-/g, "_");
}

function downloadBlob(blob: Blob, filename: string): void {
	const url = URL.createObjectURL(blob);
	const a = document.createElement("a");
	a.href = url;
	a.download = filename;
	a.click();
	URL.revokeObjectURL(url);
}
