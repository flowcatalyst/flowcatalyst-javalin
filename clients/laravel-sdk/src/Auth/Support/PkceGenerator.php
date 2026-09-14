<?php

declare(strict_types=1);

namespace FlowCatalyst\Auth\Support;

/**
 * PKCE (Proof Key for Code Exchange) generator for OAuth 2.0.
 *
 * Implements RFC 7636 for secure authorization code flow in public clients.
 *
 * @see https://datatracker.ietf.org/doc/html/rfc7636
 */
final class PkceGenerator
{
    /**
     * Generate a cryptographically random code verifier.
     *
     * The verifier is a high-entropy cryptographic random string
     * between 43 and 128 characters (RFC 7636 Section 4.1).
     */
    public static function generateCodeVerifier(int $length = 64): string
    {
        $bytes = random_bytes($length);
        return rtrim(strtr(base64_encode($bytes), '+/', '-_'), '=');
    }

    /**
     * Generate a code challenge from a code verifier using S256.
     *
     * The S256 method is: BASE64URL(SHA256(code_verifier))
     */
    public static function generateCodeChallenge(string $codeVerifier): string
    {
        $hash = hash('sha256', $codeVerifier, true);
        return rtrim(strtr(base64_encode($hash), '+/', '-_'), '=');
    }

    /**
     * Generate a random state parameter for CSRF protection.
     */
    public static function generateState(int $length = 32): string
    {
        $bytes = random_bytes($length);
        return rtrim(strtr(base64_encode($bytes), '+/', '-_'), '=');
    }

    /**
     * Generate a random nonce for replay protection.
     */
    public static function generateNonce(int $length = 32): string
    {
        $bytes = random_bytes($length);
        return rtrim(strtr(base64_encode($bytes), '+/', '-_'), '=');
    }
}
