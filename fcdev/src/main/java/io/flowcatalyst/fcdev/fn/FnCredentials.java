package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.fcdev.DevEnv;
import io.flowcatalyst.fcdev.DevPaths;
import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/// Where `fcdev fn` gets its platform credentials
/// (`docs/spec/function-developer-surface.md` §1, §2). Resolution order,
/// first complete match wins:
///
///  1. `--client-id`/`--client-secret` flags (+ `--platform-url` flag or
///     `FLOWCATALYST_PLATFORM_URL`);
///  2. `FLOWCATALYST_CLIENT_ID`/`FLOWCATALYST_CLIENT_SECRET` env (+
///     `--platform-url`/`FLOWCATALYST_PLATFORM_URL`);
///  3. `fn-cli.json` (written by `fcdev start`, [DevPaths#fnCliCredentialsPath]) —
///     also the only source of `hostUrl` (`fn invoke`'s default target);
///  4. a one-line error naming all three places that were checked.
public record FnCredentials(String platformUrl, String clientId, String clientSecret, String hostUrl) {

    public FnCredentials {
        Objects.requireNonNull(platformUrl, "platformUrl");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(clientSecret, "clientSecret");
    }

    /// Raised when none of the three sources yields a full set of
    /// credentials — the CLI prints [Throwable#getMessage()] as its one line
    /// (exit 1, never a stack trace).
    public static final class MissingException extends RuntimeException {
        public MissingException(String message) {
            super(message);
        }
    }

    public static FnCredentials resolve(String flagPlatformUrl, String flagClientId, String flagClientSecret,
                                         DevEnv env, DevPaths paths) {
        String envUrl = blankToNull(env.get("FLOWCATALYST_PLATFORM_URL"));
        String url = firstNonBlank(flagPlatformUrl, envUrl);

        String flagsId = blankToNull(flagClientId);
        String flagsSecret = blankToNull(flagClientSecret);
        String envId = blankToNull(env.get("FLOWCATALYST_CLIENT_ID"));
        String envSecret = blankToNull(env.get("FLOWCATALYST_CLIENT_SECRET"));

        Path file = paths.fnCliCredentialsPath();
        FileCreds fromFile = readFile(file);

        // 1. flags, 2. env (both need a resolvable platform URL — from a flag,
        // FLOWCATALYST_PLATFORM_URL, or the file, in that order).
        String id = firstNonBlank(flagsId, envId);
        String secret = firstNonBlank(flagsSecret, envSecret);
        if (id != null && secret != null) {
            String resolvedUrl = firstNonBlank(url, fromFile == null ? null : fromFile.platformUrl());
            if (resolvedUrl == null) {
                throw missing(file);
            }
            return new FnCredentials(resolvedUrl, id, secret, fromFile == null ? null : fromFile.hostUrl());
        }

        // 3. fn-cli.json in full.
        if (fromFile != null && fromFile.clientId() != null && fromFile.clientSecret() != null) {
            String resolvedUrl = firstNonBlank(url, fromFile.platformUrl());
            if (resolvedUrl == null) {
                throw missing(file);
            }
            return new FnCredentials(resolvedUrl, fromFile.clientId(), fromFile.clientSecret(), fromFile.hostUrl());
        }

        // 4. nowhere to look.
        throw missing(file);
    }

    /// `fn invoke`'s own default target (`--host-url`, else this, else
    /// `http://127.0.0.1:8090`) — reads only `hostUrl` from `fn-cli.json`
    /// without requiring a full, resolvable credential set (an unversioned
    /// `--webhook`/`auth: none` invoke needs no client-credentials token at
    /// all). `null` when the file is absent, unreadable, or carries no
    /// `hostUrl`.
    public static String hostUrlFromFile(DevPaths paths) {
        FileCreds creds = readFile(paths.fnCliCredentialsPath());
        return creds == null ? null : creds.hostUrl();
    }

    private static MissingException missing(Path file) {
        return new MissingException("no FlowCatalyst credentials found — looked for: "
                + "(1) --client-id/--client-secret flags, "
                + "(2) FLOWCATALYST_CLIENT_ID/FLOWCATALYST_CLIENT_SECRET environment variables, "
                + "(3) " + file);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b != null && !b.isBlank() ? b : null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private record FileCreds(String platformUrl, String clientId, String clientSecret, String hostUrl) {
    }

    private static FileCreds readFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            JsonNode node = Json.MAPPER.readTree(Files.readString(path));
            return new FileCreds(textOrNull(node, "platformUrl"), textOrNull(node, "clientId"),
                    textOrNull(node, "clientSecret"), textOrNull(node, "hostUrl"));
        } catch (IOException | RuntimeException e) {
            // A corrupt/unreadable fn-cli.json is treated as "no file" — the
            // caller falls through to flags/env, or the combined error below.
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : blankToNull(v.asString());
    }
}
