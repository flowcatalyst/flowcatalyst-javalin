import { strict as assert } from "node:assert";
import { describe, it } from "node:test";
import {
	CookieSessionStore,
	type CookieAttrs,
} from "../../src/fastify/session/cookie-store.js";
import { generateSessionSecret } from "../../src/fastify/crypto.js";
import type { SessionPayload } from "../../src/fastify/session/types.js";
import type { FastifyReply, FastifyRequest } from "fastify";

const cookieOptions: CookieAttrs = {
	path: "/",
	httpOnly: true,
	secure: true,
	sameSite: "lax",
	maxAge: 3600,
};

interface CapturedCookie {
	name: string;
	value: string;
	opts: CookieAttrs;
}

function fakeReply(): FastifyReply & { captured: CapturedCookie | null; cleared: { name: string } | null } {
	const r: Record<string, unknown> = {
		captured: null,
		cleared: null,
		setCookie(name: string, value: string, opts: CookieAttrs) {
			r.captured = { name, value, opts };
			return r;
		},
		clearCookie(name: string) {
			r.cleared = { name };
			return r;
		},
	};
	return r as unknown as FastifyReply & {
		captured: CapturedCookie | null;
		cleared: { name: string } | null;
	};
}

function fakeRequest(cookies: Record<string, string>): FastifyRequest {
	return { cookies } as unknown as FastifyRequest;
}

function newSession(over: Partial<SessionPayload> = {}): SessionPayload {
	return {
		principal: {
			id: "prn_x",
			type: "USER",
			scope: "client",
			name: "Tester",
			clients: ["clt_a"],
			roles: ["r"],
			applications: ["app"],
		},
		tokens: {
			accessToken: "at",
			accessTokenExpiresAt: Date.now() + 5 * 60_000,
			refreshToken: "rt",
		},
		sessionData: { foo: "bar" },
		expiresAt: Date.now() + 60 * 60_000,
		...over,
	};
}

describe("CookieSessionStore", () => {
	const store = new CookieSessionStore({
		cookieName: "fc_test",
		secret: generateSessionSecret(),
		cookieOptions,
	});

	it("write → read round-trip preserves the session", async () => {
		const reply = fakeReply();
		const original = newSession();
		await store.write(reply, original);
		assert.ok(reply.captured);
		assert.equal(reply.captured.name, "fc_test");

		const req = fakeRequest({ fc_test: reply.captured.value });
		const read = await store.read(req);
		assert.deepEqual(read, original);
	});

	it("read returns null when cookie absent", async () => {
		const req = fakeRequest({});
		assert.equal(await store.read(req), null);
	});

	it("read returns null when session is expired", async () => {
		const reply = fakeReply();
		await store.write(reply, newSession({ expiresAt: Date.now() - 1000 }));
		const req = fakeRequest({ fc_test: reply.captured!.value });
		assert.equal(await store.read(req), null);
	});

	it("read returns null when cookie value is tampered", async () => {
		const reply = fakeReply();
		await store.write(reply, newSession());
		const envelope = reply.captured!.value;
		const parts = envelope.split(".");
		const bytes = Buffer.from(parts[2]!, "base64url");
		bytes[0] = bytes[0]! ^ 0xff;
		const tampered = `${parts[0]}.${parts[1]}.${bytes.toString("base64url")}`;
		const req = fakeRequest({ fc_test: tampered });
		assert.equal(await store.read(req), null);
	});

	it("clear emits clearCookie", async () => {
		const reply = fakeReply();
		await store.clear(fakeRequest({}), reply);
		assert.equal(reply.cleared?.name, "fc_test");
	});
});
