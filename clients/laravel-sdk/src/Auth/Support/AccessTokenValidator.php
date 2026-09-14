<?php

declare(strict_types=1);

namespace FlowCatalyst\Auth\Support;

use FlowCatalyst\Auth\DTOs\FlowCatalystUser;

/**
 * Validates FlowCatalyst access tokens (Bearer) using JWKS — RS256 only.
 *
 * Verification path:
 *   1. Parse JWT, lift `kid` from the header.
 *   2. Look up the JWK by `kid` in {@see JwksCache::keys()}.
 *   3. Reconstruct the RSA public key from JWK `n` / `e` (PEM via SubjectPublicKeyInfo).
 *   4. `openssl_verify` the signature over `header.payload`.
 *   5. Check `exp`, `nbf`, `iss` (best-effort) and optional `aud`.
 *
 * Implemented without a JWT library to keep the dependency surface small
 * and avoid security-advisory churn in the JWT-library ecosystem. The same
 * token shape applies to both `authorization_code` and `client_credentials`
 * grants.
 */
final class AccessTokenValidator
{
    public function __construct(
        private readonly JwksCache $jwks,
        private readonly string $baseUrl,
        private readonly ?string $expectedAudience = null,
    ) {}

    /**
     * Validate a Bearer access token and return a Principal. Returns null
     * on any validation failure (caller decides how to respond).
     */
    public function validate(string $token): ?FlowCatalystUser
    {
        $parts = explode('.', $token);
        if (count($parts) !== 3) {
            return null;
        }
        [$header64, $payload64, $signature64] = $parts;

        $header = $this->b64uJson($header64);
        $payload = $this->b64uJson($payload64);
        $signature = $this->b64uDecode($signature64);
        if ($header === null || $payload === null || $signature === '') {
            return null;
        }
        if (($header['alg'] ?? null) !== 'RS256') {
            return null;
        }
        $kid = $header['kid'] ?? null;
        if (!is_string($kid) || $kid === '') {
            return null;
        }

        $keys = $this->jwks->keys($this->baseUrl);
        if (!isset($keys[$kid])) {
            // JWKS may have rotated since cache fill; invalidate and retry once.
            $this->jwks->invalidate($this->baseUrl);
            $keys = $this->jwks->keys($this->baseUrl);
            if (!isset($keys[$kid])) {
                return null;
            }
        }

        $pem = $this->jwkToPem($keys[$kid]);
        if ($pem === null) {
            return null;
        }

        $signingInput = "{$header64}.{$payload64}";
        $verifyResult = openssl_verify($signingInput, $signature, $pem, OPENSSL_ALGO_SHA256);
        if ($verifyResult !== 1) {
            return null;
        }

        // Standard temporal claim checks.
        $now = time();
        if (isset($payload['exp']) && is_numeric($payload['exp']) && (int) $payload['exp'] < $now) {
            return null;
        }
        if (isset($payload['nbf']) && is_numeric($payload['nbf']) && (int) $payload['nbf'] > $now + 60) {
            return null;
        }
        if (!isset($payload['sub']) || !is_string($payload['sub'])) {
            return null;
        }
        if ($this->expectedAudience !== null) {
            $aud = $payload['aud'] ?? null;
            $audMatch = is_string($aud)
                ? $aud === $this->expectedAudience
                : (is_array($aud) && in_array($this->expectedAudience, $aud, true));
            if (!$audMatch) {
                return null;
            }
        }

        return FlowCatalystUser::fromAccessTokenClaims(
            claims: $payload,
            accessToken: $token,
            mechanism: 'bearer',
        );
    }

    private function b64uDecode(string $s): string
    {
        $remainder = strlen($s) % 4;
        if ($remainder > 0) {
            $s .= str_repeat('=', 4 - $remainder);
        }
        $decoded = base64_decode(strtr($s, '-_', '+/'), true);
        return $decoded === false ? '' : $decoded;
    }

    /**
     * @return array<string, mixed>|null
     */
    private function b64uJson(string $s): ?array
    {
        $raw = $this->b64uDecode($s);
        if ($raw === '') {
            return null;
        }
        try {
            $decoded = json_decode($raw, true, 512, JSON_THROW_ON_ERROR);
        } catch (\Throwable) {
            return null;
        }
        return is_array($decoded) ? $decoded : null;
    }

    /**
     * Convert an RSA JWK (n, e) to a PEM-encoded SubjectPublicKeyInfo so we
     * can hand it to `openssl_verify`. Pure DER assembly; no external libs.
     *
     * @param array<string, mixed> $jwk
     */
    private function jwkToPem(array $jwk): ?string
    {
        if (($jwk['kty'] ?? null) !== 'RSA' || !isset($jwk['n'], $jwk['e'])) {
            return null;
        }
        if (!is_string($jwk['n']) || !is_string($jwk['e'])) {
            return null;
        }
        $n = $this->b64uDecode($jwk['n']);
        $e = $this->b64uDecode($jwk['e']);
        if ($n === '' || $e === '') {
            return null;
        }

        $rsaPubKey = $this->derSequence(
            $this->derInteger($n) . $this->derInteger($e),
        );
        // AlgorithmIdentifier OID: rsaEncryption (1.2.840.113549.1.1.1) + NULL params.
        $algorithmIdentifier = $this->derSequence(
            $this->derRaw("\x06\x09\x2a\x86\x48\x86\xf7\x0d\x01\x01\x01\x05\x00"),
        );
        $bitString = $this->derBitString($rsaPubKey);
        $spki = $this->derSequence($algorithmIdentifier . $bitString);

        return "-----BEGIN PUBLIC KEY-----\n"
            . chunk_split(base64_encode($spki), 64, "\n")
            . "-----END PUBLIC KEY-----\n";
    }

    private function derLength(int $length): string
    {
        if ($length < 0x80) {
            return chr($length);
        }
        $bytes = ltrim(pack('N', $length), "\x00");
        return chr(0x80 | strlen($bytes)) . $bytes;
    }

    private function derSequence(string $contents): string
    {
        return "\x30" . $this->derLength(strlen($contents)) . $contents;
    }

    private function derInteger(string $bytes): string
    {
        // INTEGER must be twos-complement; for positive integers with MSB set
        // we have to prepend a zero byte to keep them positive.
        if ($bytes !== '' && (ord($bytes[0]) & 0x80) !== 0) {
            $bytes = "\x00" . $bytes;
        }
        return "\x02" . $this->derLength(strlen($bytes)) . $bytes;
    }

    private function derBitString(string $bytes): string
    {
        return "\x03" . $this->derLength(strlen($bytes) + 1) . "\x00" . $bytes;
    }

    private function derRaw(string $bytes): string
    {
        return $bytes;
    }
}
