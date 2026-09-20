import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/// B4's "function B": `concurrency` virtual threads each looping
/// closed-loop (no rate limit — flat out) against `url` for `seconds`
/// seconds. Used instead of N parallel `curl` processes: process
/// fork/exec overhead per curl invocation (a few ms) would otherwise
/// dominate over the ~1ms of server-side CPU work B is supposed to be
/// generating, understating how hard B actually hammers the host.
///
/// Usage: java Hammer.java <url> <concurrency> <seconds>
public class Hammer {
    public static void main(String[] args) throws Exception {
        String url = args[0];
        int concurrency = Integer.parseInt(args[1]);
        int seconds = Integer.parseInt(args[2]);
        HttpClient client = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(5)).build();
        AtomicLong ok = new AtomicLong(), busy = new AtomicLong(), err = new AtomicLong();
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        CountDownLatch latch = new CountDownLatch(concurrency);
        for (int i = 0; i < concurrency; i++) {
            Thread.ofVirtual().start(() -> {
                while (System.nanoTime() < end) {
                    try {
                        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(10)).GET().build();
                        HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                        if (resp.statusCode() == 200) ok.incrementAndGet();
                        else if (resp.statusCode() == 429) busy.incrementAndGet();
                        else err.incrementAndGet();
                    } catch (Exception e) {
                        err.incrementAndGet();
                    }
                }
                latch.countDown();
            });
        }
        latch.await(seconds + 15L, TimeUnit.SECONDS);
        System.out.printf("hammer done: ok=%d busy429=%d err=%d%n", ok.get(), busy.get(), err.get());
    }
}
