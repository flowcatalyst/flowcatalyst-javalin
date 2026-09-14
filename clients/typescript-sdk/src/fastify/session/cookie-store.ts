/**
 * CookieSessionStore — default backend. The encrypted cookie IS the session.
 *
 * Pros: zero infra, stateless, no DB hop on every request.
 * Cons: ~4KB hard browser cookie limit. Once `sessionData` gets fat, switch
 * to {@link PgSessionStore} or {@link RedisSessionStore}.
 *
 * Cookie shape: `<envelope>` produced by {@link SessionCrypto}.
 */

import type { FastifyReply, FastifyRequest } from "fastify";
import { createSessionCrypto, type SessionCrypto } from "../crypto.js";
import type { SessionPayload, SessionStore } from "./types.js";

export interface CookieSessionStoreOptions {
	cookieName: string;
	secret: string | readonly string[];
	cookieOptions: CookieAttrs;
}

export interface CookieAttrs {
	path: string;
	domain?: string;
	httpOnly: boolean;
	secure: boolean;
	sameSite: "lax" | "strict" | "none";
	maxAge: number; // seconds
}

export class CookieSessionStore implements SessionStore {
	private readonly crypto: SessionCrypto;
	private readonly opts: CookieSessionStoreOptions;

	constructor(opts: CookieSessionStoreOptions) {
		this.opts = opts;
		this.crypto = createSessionCrypto(opts.secret);
	}

	async read<TData>(
		req: FastifyRequest,
	): Promise<SessionPayload<TData> | null> {
		const raw = req.cookies?.[this.opts.cookieName];
		if (!raw) return null;
		const json = await this.crypto.decrypt(raw);
		if (!json) return null;
		try {
			const parsed = JSON.parse(json) as SessionPayload<TData>;
			if (typeof parsed.expiresAt !== "number" || parsed.expiresAt < Date.now()) {
				return null;
			}
			return parsed;
		} catch {
			return null;
		}
	}

	async write<TData>(
		reply: FastifyReply,
		session: SessionPayload<TData>,
	): Promise<void> {
		const envelope = await this.crypto.encrypt(JSON.stringify(session));
		reply.setCookie(this.opts.cookieName, envelope, this.opts.cookieOptions);
	}

	async clear(_req: FastifyRequest, reply: FastifyReply): Promise<void> {
		reply.clearCookie(this.opts.cookieName, {
			path: this.opts.cookieOptions.path,
			...(this.opts.cookieOptions.domain
				? { domain: this.opts.cookieOptions.domain }
				: {}),
		});
	}
}
