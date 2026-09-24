package io.flowcatalyst.fcdev;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.ThreadLocalRandom;

/// A concrete port for a test that cannot pass `0` (the fn port: the platform's
/// `FC_FN_POOL_URL` is computed before the function host binds).
///
/// Probing with `new ServerSocket(0)` and releasing is unsafe on macOS: the port
/// comes from the ephemeral range, so embedded Postgres (also given port `0`,
/// bound on `localhost`) or any outgoing socket can take it next, and a later
/// WILDCARD bind still succeeds beside a `127.0.0.1` holder (SO_REUSEADDR) —
/// the client dialling `127.0.0.1` then reaches the other listener ("HTTP/1.1
/// header parser received no bytes"). A port below the ephemeral range
/// (49152+ on macOS, 32768+ on Linux) is never handed out by the OS, and it is
/// probed on BOTH the loopback and the wildcard address.
public final class TestPorts {

    private static final int LOW = 20_000;
    private static final int HIGH = 32_000;

    private TestPorts() {}

    public static int belowEphemeralRange() {
        for (int attempt = 0; attempt < 200; attempt++) {
            int port = ThreadLocalRandom.current().nextInt(LOW, HIGH);
            if (free(InetAddress.getLoopbackAddress(), port) && free(null, port)) {
                return port;
            }
        }
        throw new IllegalStateException("no free port in [" + LOW + ", " + HIGH + ")");
    }

    private static boolean free(InetAddress address, int port) {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(address, port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
