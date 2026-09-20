import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/// Closed-loop load generator for the "typical fixture under load" check
/// (docs/function-runner-report.md's own "one load check the first
/// benchmark lacked"): `concurrency` virtual threads each loop
/// request-then-immediately-next (wrk's own model, but Java so it can post a
/// body and round-robin across N distinct addresses without a Lua script) for
/// `seconds` seconds, spread round-robin across `addressCount` addresses
/// under `baseUrl/functions/<addressPrefix>fNNN/x`, each POST carrying a
/// fixed ~2 KiB JSON body valid against the "typical" fixture's own schema
/// (`fixtures/typical/.../TypicalFn.java`: required name/amount, optional
/// note up to 2000 chars — `note` is padded to land near 2 KiB total).
///
/// Usage: java LoadClient.java <baseUrl> <addressPrefix> <addressCount> <concurrency> <seconds>
///   e.g. java LoadClient.java http://127.0.0.1:18080 bench.typical.f 100 256 60
public class LoadClient {

    public static void main(String[] args) throws Exception {
        String baseUrl = args[0];
        String addressPrefix = args[1];
        int addressCount = Integer.parseInt(args[2]);
        int concurrency = Integer.parseInt(args[3]);
        int seconds = Integer.parseInt(args[4]);

        // ~2 KiB JSON body, valid against {required: name, amount; optional note <=2000}.
        String padding = "x".repeat(1930);
        byte[] body = ("{\"name\":\"load-test\",\"amount\":1,\"note\":\"" + padding + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        System.err.println("body bytes=" + body.length);

        // HTTP_1_1 explicitly: the JDK client defaults to negotiating h2c against this
        // listener (docs/spec/http-transport.md), which multiplexes many virtual-thread
        // requests onto ONE connection and hits its max-concurrent-streams limit long
        // before the server itself is under any real load ("too many concurrent streams",
        // a client-side IOException, found while first running this) -- HTTP/1.1 opens one
        // connection per concurrent request instead, matching wrk's own closed-loop model.
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        AtomicInteger addressCursor = new AtomicInteger();
        AtomicLong requestCount = new AtomicLong();
        AtomicLong errorCount = new AtomicLong();
        AtomicLong non200Count = new AtomicLong();
        List<Long> latenciesNs = new CopyOnWriteArrayList<>();

        long endAt = System.nanoTime() + seconds * 1_000_000_000L;
        CountDownLatch done = new CountDownLatch(concurrency);
        Thread[] workers = new Thread[concurrency];
        for (int i = 0; i < concurrency; i++) {
            workers[i] = Thread.ofVirtual().start(() -> {
                try {
                    while (System.nanoTime() < endAt) {
                        int idx = addressCursor.getAndIncrement() % addressCount;
                        String address = String.format("%s%03d", addressPrefix, idx);
                        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/functions/" + address + "/x"))
                                .timeout(Duration.ofSeconds(10))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                                .build();
                        long t0 = System.nanoTime();
                        try {
                            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                            long elapsed = System.nanoTime() - t0;
                            requestCount.incrementAndGet();
                            latenciesNs.add(elapsed);
                            if (resp.statusCode() != 200) {
                                non200Count.incrementAndGet();
                            }
                        } catch (Exception e) {
                            requestCount.incrementAndGet();
                            errorCount.incrementAndGet();
                            if (errorCount.get() <= 5) {
                                System.err.println("ERROR SAMPLE: " + e);
                            }
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();

        List<Long> sorted = new ArrayList<>(latenciesNs);
        Collections.sort(sorted);
        int n = sorted.size();
        double p50Ms = n == 0 ? -1 : sorted.get((int) (n * 0.50)) / 1_000_000.0;
        double p99Ms = n == 0 ? -1 : sorted.get(Math.min(n - 1, (int) (n * 0.99))) / 1_000_000.0;
        double meanMs = n == 0 ? -1 : sorted.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        double reqPerSec = n / (double) seconds;

        System.out.println("REQUESTS=" + requestCount.get());
        System.out.println("ERRORS=" + errorCount.get());
        System.out.println("NON_200=" + non200Count.get());
        System.out.println("REQ_PER_SEC=" + reqPerSec);
        System.out.println("P50_MS=" + p50Ms);
        System.out.println("P99_MS=" + p99Ms);
        System.out.println("MEAN_MS=" + meanMs);
        System.out.println("DONE");
    }
}
