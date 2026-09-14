/**
 * SessionStore — pluggable session backend.
 *
 * Two shapes ship in-box:
 *   - {@link CookieSessionStore} (default) — the encrypted cookie IS the session.
 *     No server-side storage; payload round-trips on every request.
 *   - {@link PgSessionStore} / {@link RedisSessionStore} — cookie holds an
 *     opaque session id; the payload lives in Postgres or Redis. Cookie size
 *     stays small even with large `sessionData`, and sessions can be revoked
 *     server-side.
 *
 * The cookie name + encryption are managed by the store; the plugin just
 * calls `read`/`write`/`clear` with the Fastify request/reply.
 */

import type { FastifyReply, FastifyRequest } from "fastify";
import type { PrincipalSnapshot } from "../principal.js";

/**
 * Payload persisted in the session backend. `principal` is the token-derived
 * identity at login time. `tokens` lets us refresh the FC access token mid-
 * session without forcing a re-login. `sessionData` is the app's bag.
 */
export interface SessionPayload<TData = Record<string, unknown>> {
	principal: Omit<PrincipalSnapshot<TData>, "mechanism" | "sessionData">;
	tokens: SessionTokens;
	sessionData: TData;
	/** Unix ms — when this session expires entirely. */
	expiresAt: number;
}

export interface SessionTokens {
	accessToken: string;
	/** Unix ms — when accessToken expires. */
	accessTokenExpiresAt: number;
	refreshToken?: string;
}

export interface SessionStore {
	read<TData>(req: FastifyRequest): Promise<SessionPayload<TData> | null>;
	write<TData>(
		reply: FastifyReply,
		session: SessionPayload<TData>,
	): Promise<void>;
	clear(req: FastifyRequest, reply: FastifyReply): Promise<void>;
}
