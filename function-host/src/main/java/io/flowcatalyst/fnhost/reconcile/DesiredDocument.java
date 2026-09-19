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
import java.util.List;
import java.util.Objects;

/// The host's own reading of the wire document `GET
/// /control/functions/desired-state` returns (`docs/spec/function-api.md`
/// §6.1), parsed leniently with [Json#MAPPER] — a newer platform may add
/// keys this host does not know about, and one bad entry must never take
/// the rest of the document down (`docs/spec/function-host-reconciler.md`
/// §1.1).
public record DesiredDocument(List<Entry> functions, List<UnloadRef> unload, List<UnreadableEntry> unreadable) {

    private static final Logger LOG = LoggerFactory.getLogger(DesiredDocument.class);

    public DesiredDocument {
        functions = List.copyOf(functions);
        unload = List.copyOf(unload);
        unreadable = List.copyOf(unreadable);
    }

    public enum Role {
        LIVE, CANDIDATE
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
    public record Entry(FunctionAddress address, String functionId, String versionId, int version, Role role,
                         Mode mode, Digest digest, String artifactRef, String signatureBundle,
                         SignerIdentity signer, Manifest manifest, String webhookSigningSecret,
                         String applicationId, String clientId) {
        public Entry {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(functionId, "functionId");
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(digest, "digest");
            Objects.requireNonNull(artifactRef, "artifactRef");
            Objects.requireNonNull(manifest, "manifest");
        }

        /// Masks the signing secret (spec §6: "the host never logs it" —
        /// `CONVENTIONS.md` §8's "a carrier of key material masks `toString`").
        @Override
        public String toString() {
            return "Entry[address=" + address + ", functionId=" + functionId + ", versionId=" + versionId
                    + ", version=" + version + ", role=" + role + ", mode=" + mode + ", digest=" + digest
                    + ", artifactRef=" + artifactRef
                    + ", signatureBundle=" + (signatureBundle == null ? "null" : signatureBundle.length() + " chars")
                    + ", signer=" + signer + ", manifest=" + manifest
                    + ", webhookSigningSecret=" + (webhookSigningSecret == null ? "null" : "<redacted>")
                    + ", applicationId=" + applicationId + ", clientId=" + clientId + "]";
        }
    }

    /// One entry of `unload` (spec §6.1): a (address, version) the host must
    /// close and drop if it has it.
    public record UnloadRef(FunctionAddress address, int version) {
        public UnloadRef {
            Objects.requireNonNull(address, "address");
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

        return new DesiredDocument(functions, unload, unreadable);
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
        return new Entry(address, functionId, versionId, version, role, mode, digest, artifactRef, signatureBundle,
                signer, manifest, webhookSigningSecret, applicationId, clientId);
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
