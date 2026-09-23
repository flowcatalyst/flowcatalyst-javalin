package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.SignerIdentity;
import io.flowcatalyst.platform.shared.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// The host's own reading of the wire document `GET
/// /control/functions/desired-state` returns (`docs/spec/function-api.md`
/// §6.1), parsed leniently with [Json#MAPPER] — a newer platform may add
/// keys this host does not know about, and one bad entry must never take
/// the rest of the document down (`docs/spec/function-host-reconciler.md`
/// §1.1).
public record DesiredDocument(List<Entry> functions, List<UnloadRef> unload, List<UnreadableEntry> unreadable,
                              List<PublicRouteRef> publicRoutes) {

    private static final Logger LOG = LoggerFactory.getLogger(DesiredDocument.class);

    public DesiredDocument {
        functions = List.copyOf(functions);
        unload = List.copyOf(unload);
        unreadable = List.copyOf(unreadable);
        publicRoutes = List.copyOf(publicRoutes);
    }

    /// Convenience constructor for every existing test fixture that built a
    /// document before `publicRoutes` existed — `publicRoutes` empty. Kept so
    /// this slice (`function-public-routes.md` §1: "no use yet") does not
    /// need to touch dozens of unrelated `ReconcilerTest`/`FnHttpTestSupport`
    /// call sites for a field nothing reads yet.
    public DesiredDocument(List<Entry> functions, List<UnloadRef> unload, List<UnreadableEntry> unreadable) {
        this(functions, unload, unreadable, List.of());
    }

    public enum Role {
        LIVE, CANDIDATE, ALIAS
    }

    public enum Mode {
        WARM, LAZY
    }

    /// One entry of `functions` (spec §6.1) the host could read in full:
    /// address, both TSIDs, the version number, its role and load mode, the
    /// artifact's digest/ref/bundle, the recorded signer (spec
    /// `function-host-reconciler.md` §0 — `null` when the version was
    /// published with signatures off), its manifest (read leniently through
    /// [Manifest#readStored], the same reader the platform's own repository
    /// uses for a foreign JSON shape), the application's webhook signing
    /// secret (spec `function-invocation.md` §6, R9 — present only for a
    /// function with a `webhook` endpoint), and `applicationId`/`clientId`
    /// (spec `function-host-listener.md` §1) — the function's owner, used to
    /// decide *reach* for a versioned call (`function-invocation.md` §4).
    /// `applicationId` is ALWAYS present (a platform-owned function still
    /// belongs to an application); only `clientId` is `null` for one.
    /// `config`/`secrets`/`missingSettings` (spec `function-context.md` §1,
    /// D4a) are parsed here but have no caller yet (`HostFunctionContext` is
    /// D4b, a later slice) — read leniently like everything else: an absent
    /// or malformed `config`/`secrets` object reads as empty rather than
    /// failing the entry, and a non-string `missingSettings` entry is dropped.
    /// @param aliases the named (non-`live`) aliases pointing at this
    ///                entry's version (spec `function-zones-and-aliases.md`
    ///                §5), sorted; `[]` when none — parsed leniently like
    ///                everything else here: a non-string entry is dropped.
    public record Entry(FunctionAddress address, String functionId, String versionId, int version, Role role,
                         Mode mode, Digest digest, String artifactRef, String signatureBundle,
                         SignerIdentity signer, Manifest manifest, String webhookSigningSecret,
                         String applicationId, String clientId, Map<String, String> config,
                         Map<String, String> secrets, List<String> missingSettings, List<String> aliases) {
        public Entry {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(functionId, "functionId");
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(digest, "digest");
            Objects.requireNonNull(artifactRef, "artifactRef");
            Objects.requireNonNull(manifest, "manifest");
            config = config == null ? Map.of() : Map.copyOf(config);
            secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
            missingSettings = missingSettings == null ? List.of() : List.copyOf(missingSettings);
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }

        /// Masks the signing secret AND `secrets` (spec §6: "the host never
        /// logs it"; `function-context.md` §1: "same masking discipline as
        /// `webhookSigningSecret`" — `CONVENTIONS.md` §8's "a carrier of key
        /// material masks `toString`"). `config` is not secret and prints
        /// plainly.
        @Override
        public String toString() {
            return "Entry[address=" + address + ", functionId=" + functionId + ", versionId=" + versionId
                    + ", version=" + version + ", role=" + role + ", mode=" + mode + ", digest=" + digest
                    + ", artifactRef=" + artifactRef
                    + ", signatureBundle=" + (signatureBundle == null ? "null" : signatureBundle.length() + " chars")
                    + ", signer=" + signer + ", manifest=" + manifest
                    + ", webhookSigningSecret=" + (webhookSigningSecret == null ? "null" : "<redacted>")
                    + ", applicationId=" + applicationId + ", clientId=" + clientId + ", config=" + config
                    + ", secrets=" + (secrets.isEmpty() ? "{}" : secrets.keySet() + " (values redacted)")
                    + ", missingSettings=" + missingSettings + ", aliases=" + aliases + "]";
        }
    }

    /// One entry of `unload` (spec §6.1): a (address, version) the host must
    /// close and drop if it has it.
    public record UnloadRef(FunctionAddress address, int version) {
        public UnloadRef {
            Objects.requireNonNull(address, "address");
        }
    }

    /// One entry of the top-level `publicRoutes` (spec
    /// `function-public-routes.md` §2, amended
    /// `function-zones-and-aliases.md` §3): `{hostname, pathPrefix, address,
    /// aliasPrefixes}`.
    public record PublicRouteRef(String hostname, String pathPrefix, FunctionAddress address,
                                 List<String> aliasPrefixes) {
        public PublicRouteRef {
            Objects.requireNonNull(hostname, "hostname");
            Objects.requireNonNull(pathPrefix, "pathPrefix");
            Objects.requireNonNull(address, "address");
            aliasPrefixes = aliasPrefixes == null ? List.of() : List.copyOf(aliasPrefixes);
        }
    }

    /// An entry of `functions` the host could NOT read (spec §1.1: "dropped
    /// with a WARN and reported FAILED if it has an address and version, and
    /// never takes the rest of the document down with it"). Only entries
    /// whose address AND version were themselves readable ever reach here —
    /// a heartbeat entry needs both, and the platform can identify neither a
    /// `FAILED` report with no address nor make sense of one with no version.
    public record UnreadableEntry(FunctionAddress address, int version, String reason) {
        public UnreadableEntry {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /// Parses the raw response body. Unknown top-level keys are ignored
    /// (nothing here rejects them); a whole document that is not even a JSON
    /// object is a hard failure — [io.flowcatalyst.fnhost.reconcile.HttpControlPlane]
    /// maps that to [ControlPlaneException.Reason#UNAVAILABLE], the same as
    /// any other malformed response.
    public static DesiredDocument parse(String body) {
        JsonNode root = Json.MAPPER.readTree(body);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("desired-state document is not a JSON object");
        }

        List<Entry> functions = new ArrayList<>();
        List<UnreadableEntry> unreadable = new ArrayList<>();
        for (JsonNode node : root.path("functions")) {
            try {
                functions.add(parseEntry(node));
            } catch (RuntimeException e) {
                FunctionAddress address = tryParseAddress(node);
                Integer version = tryParseVersion(node);
                LOG.atWarn().setMessage("dropping unreadable desired-state entry")
                        .addKeyValue("address", address == null ? null : address.render())
                        .setCause(e)
                        .log();
                if (address != null && version != null) {
                    unreadable.add(new UnreadableEntry(address, version, describe(e)));
                }
            }
        }

        List<UnloadRef> unload = new ArrayList<>();
        for (JsonNode node : root.path("unload")) {
            try {
                unload.add(parseUnloadRef(node));
            } catch (RuntimeException e) {
                LOG.atWarn().setMessage("dropping unreadable unload entry").setCause(e).log();
            }
        }

        List<PublicRouteRef> publicRoutes = new ArrayList<>();
        for (JsonNode node : root.path("publicRoutes")) {
            try {
                publicRoutes.add(parsePublicRouteRef(node));
            } catch (RuntimeException e) {
                LOG.atWarn().setMessage("dropping unreadable publicRoutes entry").setCause(e).log();
            }
        }

        return new DesiredDocument(functions, unload, unreadable, publicRoutes);
    }

    private static PublicRouteRef parsePublicRouteRef(JsonNode node) {
        String hostname = requireText(node, "hostname");
        String pathPrefix = requireText(node, "pathPrefix");
        FunctionAddress address = FunctionAddress.parse(requireText(node, "address"));
        List<String> aliasPrefixes = readStringListField(node.path("aliasPrefixes"));
        return new PublicRouteRef(hostname, pathPrefix, address, aliasPrefixes);
    }

    private static Entry parseEntry(JsonNode node) {
        FunctionAddress address = FunctionAddress.parse(requireText(node, "address"));
        String functionId = requireText(node, "functionId");
        String versionId = requireText(node, "versionId");
        int version = requireInt(node, "version");
        Role role = parseRole(requireText(node, "role"));
        Mode mode = parseMode(requireText(node, "mode"));
        Digest digest = Digest.parse(requireText(node, "digest"));
        String artifactRef = requireText(node, "artifactRef");
        String signatureBundle = optionalText(node, "signatureBundle");
        SignerIdentity signer = parseSigner(node.path("signer"));
        Manifest manifest = Manifest.readStored(node.path("manifest"));
        String webhookSigningSecret = optionalText(node, "webhookSigningSecret");
        // The platform always sends applicationId now (a platform-owned function still
        // belongs to an application; only clientId is ever omitted) — read leniently
        // regardless, same as every other field here: an older/foreign document must
        // never crash this parse (spec §1.1).
        String applicationId = optionalText(node, "applicationId");
        String clientId = optionalText(node, "clientId");
        Map<String, String> config = readStringMap(node.path("config"));
        Map<String, String> secrets = readStringMap(node.path("secrets"));
        List<String> missingSettings = readStringListField(node.path("missingSettings"));
        List<String> aliases = readStringListField(node.path("aliases"));
        return new Entry(address, functionId, versionId, version, role, mode, digest, artifactRef, signatureBundle,
                signer, manifest, webhookSigningSecret, applicationId, clientId, config, secrets, missingSettings,
                aliases);
    }

    /// Tolerant object-of-strings reader (spec §1.1): not an object, or any
    /// entry not a string, drops the whole map — a value the caller cannot
    /// trust in part is not trusted at all.
    private static Map<String, String> readStringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (var entry : node.properties()) {
            if (!entry.getValue().isString()) {
                return Map.of();
            }
            out.put(entry.getKey(), entry.getValue().asString());
        }
        return out;
    }

    /// Tolerant string-array reader: not an array reads as empty; a
    /// non-string entry is dropped rather than failing the whole list.
    private static List<String> readStringListField(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode entry : node) {
            if (entry.isString()) {
                out.add(entry.asString());
            }
        }
        return out;
    }

    private static UnloadRef parseUnloadRef(JsonNode node) {
        FunctionAddress address = FunctionAddress.parse(requireText(node, "address"));
        int version = requireInt(node, "version");
        return new UnloadRef(address, version);
    }

    private static Role parseRole(String raw) {
        return switch (raw) {
            case "live" -> Role.LIVE;
            case "candidate" -> Role.CANDIDATE;
            case "alias" -> Role.ALIAS;
            default -> throw new IllegalArgumentException("unrecognised role: " + raw);
        };
    }

    private static Mode parseMode(String raw) {
        return switch (raw) {
            case "warm" -> Mode.WARM;
            case "lazy" -> Mode.LAZY;
            default -> throw new IllegalArgumentException("unrecognised mode: " + raw);
        };
    }

    private static SignerIdentity parseSigner(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return new SignerIdentity(requireText(node, "issuer"), requireText(node, "subject"));
    }

    private static FunctionAddress tryParseAddress(JsonNode node) {
        try {
            return FunctionAddress.parse(node.path("address").asString(null));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer tryParseVersion(JsonNode node) {
        JsonNode v = node.path("version");
        return v.isIntegralNumber() && v.canConvertToInt() ? v.asInt() : null;
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException(field + " is required and must be a non-blank string");
        }
        return value.asString();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }

    private static int requireInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " is required and must be an integer");
        }
        return value.asInt();
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return "UNREADABLE:" + (message != null ? message : e.getClass().getSimpleName());
    }
}
