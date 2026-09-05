import net from "node:net";
import { describe, it, expect } from "vitest";
import { freePort, freePorts } from "../ports.js";

describe("freePort", () => {
    it("returns a port nothing is listening on yet, and it can actually be bound", async () => {
        const port = await freePort();
        expect(port).toBeGreaterThan(0);

        // The behaviour that matters: a real listener can bind the port
        // freePort() handed back. A stub that always returned, say, 0 or a
        // fixed number would pass a bare "> 0" check but fail this bind.
        const srv = net.createServer();
        await new Promise<void>((resolve, reject) => {
            srv.once("error", reject);
            srv.listen(port, "127.0.0.1", () => resolve());
        });
        await new Promise<void>((resolve) => srv.close(() => resolve()));
    });
});

describe("freePorts", () => {
    it("returns pairwise-distinct ports", async () => {
        const ports = await freePorts(5);
        expect(ports).toHaveLength(5);
        expect(new Set(ports).size).toBe(5);

        // Mutant check: if freePorts just called freePort() once and
        // repeated the result n times, `size` above would be 1, not 5.
    });
});
