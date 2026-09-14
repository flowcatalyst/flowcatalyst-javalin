/**
 * Postgres-backed session store.
 *
 * Cookie holds an opaque session id (32B random, base64url-encoded). Payload
 * lives in `fc_sessions`. Lookups filter on `expires_at > NOW()` so expired
 * rows are invisible even pre-reap; reap them lazily via {@link reapExpired}.
 *
 * Duck-typed against any node-postgres-compatible executor (`pg.Pool`,
 * `pg.PoolClient`, Drizzle underlying client).
 *
 * Run {@link initSessionSchema} once at startup (or fold into your migration
 * tool).
 */

import type { FastifyReply, FastifyRequest } from "fastify";
import type { PgQueryable } from "../../cache/types.js";
import type { CookieAttrs } from "./cookie-store.js";
import type { SessionPayload, SessionStore } from "./types.js";

export interface PgSessionStoreOptions {
	executor: PgQueryable;
	cookieName: string;
	cookieOptions: CookieAttrs;
	table?: string;
}

interface PgQueryResult<R> {
	rows?: R[];
	rowCount?: number | null;
}

export class PgSessionStore implements SessionStore {
	private readonly executor: PgQueryable;
	private readonly table: string;
	private readonly cookieName: string;
	private readonly cookieOptions: CookieAttrs;

	constructor(opts: PgSessionStoreOptions) {
		this.executor = opts.executor;
		this.table = opts.table ?? "fc_sessions";
		this.cookieName = opts.cookieName;
		this.cookieOptions = opts.cookieOptions;
	}

	async read<TData>(
		req: FastifyRequest,
	): Promise<SessionPayload<TData> | null> {
		const sid = req.cookies?.[this.cookieName];
		if (!sid) return null;
		const result = (await this.executor.query(
			`SELECT payload FROM ${this.table} WHERE sid = $1 AND expires_at > NOW()`,
			[sid],
		)) as PgQueryResult<{ payload: string | Buffer }>;
		const row = result.rows?.[0];
		if (!row) return null;
		const text = Buffer.isBuffer(row.payload)
			? row.payload.toString("utf-8")
			: row.payload;
		try {
			return JSON.parse(text) as SessionPayload<TData>;
		} catch {
			return null;
		}
	}

	async write<TData>(
		reply: FastifyReply,
		session: SessionPayload<TData>,
	): Promise<void> {
		const sid = generateSid();
		const payload = JSON.stringify(session);
		const expiresAt = new Date(session.expiresAt);
		await this.executor.query(
			`INSERT INTO ${this.table} (sid, payload, expires_at) VALUES ($1, $2, $3)
			 ON CONFLICT (sid) DO UPDATE SET payload = EXCLUDED.payload, expires_at = EXCLUDED.expires_at`,
			[sid, payload, expiresAt],
		);
		reply.setCookie(this.cookieName, sid, this.cookieOptions);
	}

	async clear(req: FastifyRequest, reply: FastifyReply): Promise<void> {
		const sid = req.cookies?.[this.cookieName];
		if (sid) {
			await this.executor.query(`DELETE FROM ${this.table} WHERE sid = $1`, [
				sid,
			]);
		}
		reply.clearCookie(this.cookieName, {
			path: this.cookieOptions.path,
			...(this.cookieOptions.domain ? { domain: this.cookieOptions.domain } : {}),
		});
	}

	/** Delete rows whose TTL has elapsed. Returns the number of rows removed. */
	async reapExpired(): Promise<number> {
		const result = (await this.executor.query(
			`DELETE FROM ${this.table} WHERE expires_at <= NOW()`,
		)) as PgQueryResult<unknown>;
		return result.rowCount ?? 0;
	}
}

function generateSid(): string {
	return Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString(
		"base64url",
	);
}

export const CREATE_SESSION_TABLE_SQL = (table = "fc_sessions"): string => `
CREATE TABLE IF NOT EXISTS ${table} (
	sid         TEXT PRIMARY KEY,
	payload     TEXT NOT NULL,
	expires_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS ${table}_expires_at_idx ON ${table} (expires_at);
`;

export async function initSessionSchema(
	executor: PgQueryable,
	table = "fc_sessions",
): Promise<void> {
	await executor.query(CREATE_SESSION_TABLE_SQL(table));
}
