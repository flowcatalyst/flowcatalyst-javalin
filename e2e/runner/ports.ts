import net from "node:net";

/// Asks the OS for a free TCP port by binding to port 0, reading back
/// whatever it chose, then releasing it. There is a race (something else
/// could grab the port before the caller binds it) but it is the same race
/// every "find a free port" helper accepts — good enough for a dev/test
/// harness, not for production port allocation.
export function freePort(): Promise<number> {
    return new Promise((resolve, reject) => {
        const srv = net.createServer();
        srv.unref();
        srv.on("error", reject);
        srv.listen(0, "127.0.0.1", () => {
            const address = srv.address();
            if (address === null || typeof address === "string") {
                srv.close();
                reject(new Error("freePort: server address was not an AddressInfo"));
                return;
            }
            const { port } = address;
            srv.close((err) => {
                if (err) reject(err);
                else resolve(port);
            });
        });
    });
}

/// `n` distinct free ports, retried until they are pairwise distinct — two
/// back-to-back `freePort()` calls can (rarely) return the same number if
/// the first socket hasn't fully released before the second binds.
export async function freePorts(n: number): Promise<number[]> {
    const out = new Set<number>();
    let guard = 0;
    while (out.size < n) {
        if (++guard > n * 20) {
            throw new Error(`freePorts: could not find ${n} distinct free ports`);
        }
        out.add(await freePort());
    }
    return [...out];
}
