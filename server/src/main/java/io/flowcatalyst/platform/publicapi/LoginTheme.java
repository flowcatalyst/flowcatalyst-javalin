package io.flowcatalyst.platform.publicapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.flowcatalyst.platform.shared.json.Json;

import java.util.Optional;

/// The stored login-theme document (spec `docs/spec/publicapi.md` §5): the
/// JSON object the admin "Login theme" page writes at
/// `GLOBAL platform/login/theme`, read back field-for-field. Every component
/// is optional (`null` = not configured) — the SPA layers this over its own
/// defaults, and the email theme ([EmailTheme]) layers it over the server's.
/// The record carries what was stored, untouched: no trimming, no colour
/// validation; those are the consumers' rules.
///
/// @param brandName          brand shown on the login card
/// @param brandSubtitle      line under the brand
/// @param logoUrl            hosted logo image URL
/// @param logoSvg            inline SVG markup
/// @param logoHeight         logo height in CSS pixels
/// @param primaryColor       CSS colour
/// @param accentColor        CSS colour
/// @param backgroundColor    CSS colour
/// @param backgroundGradient CSS `background` value
/// @param footerText         small print under the card
/// @param customCss          extra stylesheet text the SPA injects
public record LoginTheme(
        String brandName,
        String brandSubtitle,
        String logoUrl,
        String logoSvg,
        Integer logoHeight,
        String primaryColor,
        String accentColor,
        String backgroundColor,
        String backgroundGradient,
        String footerText,
        String customCss) {

    /// Nothing configured — `{}` on the wire.
    public static final LoginTheme EMPTY = new LoginTheme(null, null, null, null, null, null, null, null, null, null, null);

    /// The one reader of the stored value (spec §5 table). Blank, the JSON
    /// literal `null`, malformed JSON or a non-object document → empty, so
    /// the caller never sees a partial parse; unknown keys are ignored
    /// ([Json#MAPPER]). The caller decides whether an empty result is worth
    /// a warning (a blank value is routine; malformed JSON is not).
    public static Optional<LoginTheme> parse(String storedValue) {
        if (storedValue == null || storedValue.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(Json.read(storedValue, LoginTheme.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }
}
