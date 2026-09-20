package fixture.lean;

import io.flowcatalyst.function.Function;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.function.Result;

import java.security.MessageDigest;

/// Bench fixture — "lean" (function-host-benchmark.md): API jar only, a
/// handful of classes. Three paths, all served by one entrypoint (the
/// manifest routes everything through one catch-all endpoint, same
/// convention FnHttpTestSupport's own fixtures use):
///
///  - anything else            -> Result.ack() immediately
///  - /io                      -> Thread.sleep(5) (parked, not spinning), then ack
///  - /cpu                     -> ~1ms of CPU (calibrated below), then ack
///
/// CPU calibration (README documents how this number was produced): 11,000
/// SHA-256 digests of a 256-byte buffer measured ~1.0-1.1ms on the
/// benchmarking host (Apple Silicon, JIT-warmed, outside any container) —
/// see bench/function-host/scripts/calibrate-cpu.sh. Not re-calibrated
/// per-container-run; the actual per-request CPU time inside the container
/// is whatever B3/B4 measure, this loop is only a fixed amount of *work*.
public final class LeanFn implements Function {

    private static final int CPU_ITERATIONS = 11_000;
    private static final byte[] CPU_BUFFER = new byte[256];
    static {
        for (int i = 0; i < CPU_BUFFER.length; i++) CPU_BUFFER[i] = (byte) i;
    }

    @Override
    public Result handle(Request in, FunctionContext ctx) throws Exception {
        String path = in.path();
        if ("/io".equals(path)) {
            Thread.sleep(5);
            return Result.ack();
        }
        if ("/cpu".equals(path)) {
            doCpuWork();
            return Result.ack();
        }
        return Result.ack();
    }

    private static void doCpuWork() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] last = null;
        for (int i = 0; i < CPU_ITERATIONS; i++) {
            md.reset();
            md.update(CPU_BUFFER);
            last = md.digest();
        }
        // Sink so the JIT cannot prove the loop's result is unused and elide it.
        if (last != null && last.length == 0) {
            throw new IllegalStateException("unreachable");
        }
    }
}
