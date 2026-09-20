import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/// Fixed-rate (open-loop) HTTP client for B4 (noisy neighbour): schedules
/// one request per `1/rateHz` seconds, each on its own virtual thread, for
/// `seconds` seconds — unlike wrk's closed-loop-at-concurrency model, the
/// request rate here does not slow down if responses get slower (which is
/// exactly the point: B4 measures whether A's SLA holds under a fixed
/// external load, not how fast A can go).
///
/// Usage: java RateClient.java <url> <rateHz> <seconds>
public class RateClient {
    public static void main(String[] args) throws Exception {
        String url = args[0];
        int rateHz = Integer.parseInt(args[1]);
        int seconds = Integer.parseInt(args[2]);
        HttpClient client = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(5)).build();
        List<Long> latenciesNs = new CopyOnWriteArrayList<>();
        AtomicInteger errors = new AtomicInteger();
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(4);
        long periodNs = 1_000_000_000L / rateHz;
        long start = System.nanoTime();
        long end = start + seconds * 1_000_000_000L;
        Runnable fire = () -> {
            long t0 = System.nanoTime();
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
                HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() != 200) errors.incrementAndGet();
            } catch (Exception e) {
                errors.incrementAndGet();
            }
            latenciesNs.add(System.nanoTime() - t0);
        };
        long delay = 0;
        List<java.util.concurrent.ScheduledFuture<?>> futures = new ArrayList<>();
        while (start + delay < end) {
            long d = delay;
            futures.add(sched.schedule(fire, d, TimeUnit.NANOSECONDS));
            delay += periodNs;
        }
        for (var f : futures) {
            try { f.get(15, TimeUnit.SECONDS); } catch (Exception ignored) { }
        }
        sched.shutdown();
        List<Long> sorted = new ArrayList<>(latenciesNs);
        Collections.sort(sorted);
        int n = sorted.size();
        double p50 = n == 0 ? -1 : sorted.get((int) (n * 0.50)) / 1e6;
        double p99 = n == 0 ? -1 : sorted.get(Math.min(n - 1, (int) (n * 0.99))) / 1e6;
        System.out.printf("requests=%d errors=%d p50_ms=%.3f p99_ms=%.3f%n", n, errors.get(), p50, p99);
    }
}
