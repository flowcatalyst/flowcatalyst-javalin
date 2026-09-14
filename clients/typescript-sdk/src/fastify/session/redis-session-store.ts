/**
 * Redis-backed session store.
 *
 * Cookie holds an opaque 32-byte session id (base64url). Payload is stored
 * in Redis under `${prefix}:${sid}` with TTL enforced by Redis itself.
 *
 * Duck-typed against ioredis ({@link RedisCommandable}). For node-redis,
 * wrap your client to match.
 */

import type { FastifyReply, FastifyRequest } from "fastify";
import type { RedisCommandable } from "../../cache/redis-cache-store.js";
import type { CookieAttrs } from "./cookie-store.js";
import type { SessionPayload, SessionStore } from "./types.js";

export interface RedisSessionStoreOptions {
	client: RedisCommandable;
	cookieName: string;
	cookieOptions: CookieAttrs;
	/** Key prefix; defaults to `fc:session`. */
	prefix?: string;
}

export class RedisSessionStore implements SessionStore {
	private readonly client: RedisCommandable;
	private readonly prefix: string;
	private readonly cookieName: string;
	private readonly cookieOptions: CookieAttrs;

	constructor(opts: RedisSessionStoreOptions) {
		this.client = opts.client;
		this.prefix = opts.prefix ?? "fc:session";
		this.cookieName = opts.cookieName;
		this.cookieOptions = opts.cookieOptions;
	}

	private key(sid: string): string {
		return `${this.prefix}:${sid}`;
	}

	async read<TData>(
		req: FastifyRequest,
	): Promise<SessionPayload<TData> | null> {
		const sid = req.cookies?.[this.cookieName];
		if (!sid) return null;
		const raw = await this.client.get(this.key(sid));
		if (!raw) return null;
		try {
			return JSON.parse(raw) as SessionPayload<TData>;
		} catch {
			return null;
		}
	}

	async write<TData>(
		reply: FastifyReply,
		session: SessionPayload<TData>,
	): Promise<void> {
		const sid = generateSid();
		const ttlMs = Math.max(1, session.expiresAt - Date.now());
		await this.client.set(this.key(sid), JSON.stringify(session), "PX", ttlMs);
		reply.setCookie(this.cookieName, sid, this.cookieOptions);
	}

	async clear(req: FastifyRequest, reply: FastifyReply): Promise<void> {
		const sid = req.cookies?.[this.cookieName];
		if (sid) await this.client.del(this.key(sid));
		reply.clearCookie(this.cookieName, {
			path: this.cookieOptions.path,
			...(this.cookieOptions.domain ? { domain: this.cookieOptions.domain } : {}),
		});
	}
}

function generateSid(): string {
	return Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString(
		"base64url",
	);
}
