// Rig-integration shim, NOT part of the flowcatalyst TypeScript repo.
//
// The Go/Java/Rust routers this bench rig was built against all mount their HTTP API under a
// "/router" prefix (see server/src/main/java/io/flowcatalyst/router/api/HealthRoutes.java —
// `routes.get(p + "/health", ...)` with `p = "/router"`), and bench/router/run.sh hardcodes that
// convention: `wait_health()` polls "http://$ip:8080/router/health", and the post-run scrape
// hits "/router/metrics" and "/router/monitoring/pools" (run.sh:315,318,500,580,583).
//
// The TypeScript message-router (packages/message-router/src/app.ts) does NOT use a "/router"
// prefix — it mounts health at "/health/{live,ready,startup}", metrics at "/metrics", and pool
// stats at "/monitoring/pool-stats" (a Record<poolCode,PoolStats>, not the list shape
// /monitoring/pools would imply). Rather than editing run.sh (shared by every router this rig
// benchmarks) or the read-only TS repo, this is a tiny, dependency-free path-rewriting reverse
// proxy that sits on the port the rig expects (8080) and forwards to the real Fastify server on
// an internal port. It only touches paths the rig calls from outside the container; it never
// sees the router's own outbound HTTP to the sink (that traffic never passes through here), so
// it has no effect on delivery throughput.
import http from "node:http";

const LISTEN_PORT = Number(process.env.PROXY_PORT || 8080);
const LISTEN_HOST = process.env.PROXY_HOST || "0.0.0.0";
const UPSTREAM_PORT = Number(process.env.UPSTREAM_PORT || 18080);
const UPSTREAM_HOST = process.env.UPSTREAM_HOST || "127.0.0.1";

// Ordered rewrite rules: first match wins. Everything else passes through unchanged (so
// /health/live etc. still work directly too, for debugging).
const REWRITES = [
	[/^\/router\/health$/, "/health/live"],
	[/^\/router\/health\/(.*)$/, "/health/$1"],
	[/^\/router\/metrics$/, "/metrics"],
	[/^\/router\/monitoring\/pools$/, "/monitoring/pool-stats"],
	[/^\/router\/monitoring\/(.*)$/, "/monitoring/$1"],
	[/^\/router\/api\/(.*)$/, "/api/$1"],
];

function rewritePath(path) {
	for (const [pattern, replacement] of REWRITES) {
		if (pattern.test(path)) {
			return path.replace(pattern, replacement);
		}
	}
	return path;
}

const server = http.createServer((req, res) => {
	const [rawPath, query = ""] = (req.url || "/").split("?", 2);
	const upstreamPath = rewritePath(rawPath) + (query ? `?${query}` : "");

	const upstreamReq = http.request(
		{
			host: UPSTREAM_HOST,
			port: UPSTREAM_PORT,
			method: req.method,
			path: upstreamPath,
			headers: req.headers,
		},
		(upstreamRes) => {
			res.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers);
			upstreamRes.pipe(res);
		},
	);

	upstreamReq.on("error", (err) => {
		if (!res.headersSent) {
			res.writeHead(502, { "content-type": "application/json" });
		}
		res.end(JSON.stringify({ status: "error", message: String(err) }));
	});

	req.pipe(upstreamReq);
});

server.listen(LISTEN_PORT, LISTEN_HOST, () => {
	console.log(
		`[proxy] listening on ${LISTEN_HOST}:${LISTEN_PORT} -> ${UPSTREAM_HOST}:${UPSTREAM_PORT} (path rewrites for the /router prefix this bench rig expects)`,
	);
});
