/**
 * Session backends for the Fastify plugin.
 *
 *   - {@link CookieSessionStore} — default, AES-GCM encrypted cookie.
 *   - {@link PgSessionStore}     — Postgres, opaque session id in cookie.
 *   - {@link RedisSessionStore}  — Redis, opaque session id in cookie.
 */

export type {
	SessionPayload,
	SessionStore,
	SessionTokens,
} from "./types.js";
export {
	CookieSessionStore,
	type CookieAttrs,
	type CookieSessionStoreOptions,
} from "./cookie-store.js";
export {
	PgSessionStore,
	type PgSessionStoreOptions,
	CREATE_SESSION_TABLE_SQL,
	initSessionSchema,
} from "./pg-session-store.js";
export {
	RedisSessionStore,
	type RedisSessionStoreOptions,
} from "./redis-session-store.js";
