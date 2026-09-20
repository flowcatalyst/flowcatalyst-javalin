package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.wire.WebhookSigner;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/// `fn invoke <address>[:<version>] [--path /x] [--method POST] [--body <file>|-]
/// [-H k:v…] [--host-url] [--webhook] [--signing-secret]` (spec §2, §4 E7):
/// calls the function HOST (never the platform) at `<host-url>/functions/<address>[:<v>]<path>`.
/// A versioned call gets the CLI's own bearer token automatically
/// (`function-invocation.md` §2). `--webhook` signs the request the way a
/// subscription/scheduled delivery would — a `webhook` endpoint needs
/// `--signing-secret` explicitly: **no platform route hands an
/// application's signing secret to a caller** (checked directly against
/// `FunctionApi`/`ApplicationApi`/`ClientApi` — none exists, by design, so
/// this is not implemented as a fallback; see the final report). Exit 0 iff
/// the HTTP status is &lt; 400 — a failing function is a failing command.
@Command(name = "invoke", description = "Call a function through the function host", sortOptions = false)
public final class InvokeCommand implements Callable<Integer> {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
    boolean help;

    @Parameters(index = "0", paramLabel = "<address>[:<version>]",
            description = "full function address, optionally suffixed :<version> for a versioned call")
    String addressArg;

    @Option(names = "--path", paramLabel = "<path>", defaultValue = "", description = "the function path (default: none, i.e. /)")
    String path;

    @Option(names = "--method", paramLabel = "<verb>", defaultValue = "GET", description = "HTTP method (default: ${DEFAULT-VALUE})")
    String method;

    @Option(names = "--body", paramLabel = "<file>|-", description = "request body file, or - for stdin")
    String bodyFile;

    @Option(names = "-H", paramLabel = "<k:v>", description = "an extra request header (repeatable)")
    List<String> headerArgs;

    @Option(names = "--host-url", paramLabel = "<url>",
            description = "the function host's base URL (default: fn-cli.json's hostUrl, else http://127.0.0.1:8090)")
    String hostUrlOpt;

    @Option(names = "--webhook", description = "sign the request as the platform would for a webhook endpoint (requires --signing-secret)")
    boolean webhook;

    @Option(names = "--signing-secret", paramLabel = "<secret>", description = "the application's webhook signing secret (required with --webhook)")
    String signingSecret;

    @Spec
    CommandSpec spec;

    InputStream stdin = System.in;

    private static final String DEFAULT_HOST_URL = "http://127.0.0.1:8090";

    @Override
    public Integer call() {
        FnCommand root = FnCommand.of(spec);
        return FnCommand.runSafely(spec, () -> {
            String rawAddress = addressArg;
            Integer version = null;
            int colon = addressArg == null ? -1 : addressArg.lastIndexOf(':');
            if (colon > 0) {
                String suffix = addressArg.substring(colon + 1);
                if (!suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit)) {
                    rawAddress = addressArg.substring(0, colon);
                    version = Integer.parseInt(suffix);
                }
            }
            if (webhook && (signingSecret == null || signingSecret.isBlank())) {
                throw new CommandLine.ParameterException(spec.commandLine(),
                        "--webhook requires --signing-secret (no platform route returns an application's "
                                + "signing secret to a caller)");
            }

            byte[] body = readBody();
            Map<String, String> headers = parseHeaders();

            String hostUrl = resolveHostUrl(root);
            String url = hostUrl + "/functions/" + rawAddress + (version == null ? "" : ":" + version) + path;

            if (version != null) {
                headers.put("Authorization", "Bearer " + root.client().bearerToken());
            }
            if (webhook) {
                String timestamp = WebhookSigner.timestamp(Instant.now());
                headers.put("X-FlowCatalyst-Timestamp", timestamp);
                headers.put("X-FlowCatalyst-Signature", WebhookSigner.sign(signingSecret, timestamp, body));
            }

            FnClient.RawResponse response = root.hostClient().raw(method, url, headers, body);
            print(root, response);
            return response.status() < 400 ? 0 : 1;
        });
    }

    private String resolveHostUrl(FnCommand root) {
        if (hostUrlOpt != null && !hostUrlOpt.isBlank()) {
            return trimTrailingSlash(hostUrlOpt);
        }
        String fromFile = io.flowcatalyst.fcdev.fn.FnCredentials.hostUrlFromFile(root.paths());
        return trimTrailingSlash(fromFile != null && !fromFile.isBlank() ? fromFile : DEFAULT_HOST_URL);
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private byte[] readBody() throws IOException {
        if (bodyFile == null || bodyFile.isBlank()) {
            return new byte[0];
        }
        if (bodyFile.equals("-")) {
            return stdin.readAllBytes();
        }
        return Files.readAllBytes(Path.of(bodyFile));
    }

    private Map<String, String> parseHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String h : headerArgs == null ? List.<String>of() : headerArgs) {
            int i = h.indexOf(':');
            if (i <= 0) {
                throw new CommandLine.ParameterException(spec.commandLine(), "expected -H k:v, got \"" + h + "\"");
            }
            headers.put(h.substring(0, i).strip(), h.substring(i + 1).strip());
        }
        return headers;
    }

    private void print(FnCommand root, FnClient.RawResponse response) {
        var out = spec.commandLine().getOut();
        if (root.output() == OutputMode.JSON) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("status", response.status());
            payload.put("headers", response.headers());
            payload.put("body", response.bodyAsString());
            out.println(Json.write(payload));
            return;
        }
        out.println("HTTP " + response.status());
        response.headers().forEach((k, vs) -> vs.forEach(v -> out.println(k + ": " + v)));
        out.println();
        out.println(response.bodyAsString());
    }
}
