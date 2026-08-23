package io.flowcatalyst.platform.docs;

import java.util.Optional;

/// The one Markdown fact this package reads: a page's title is its first
/// `# ` heading (spec §2, §5). Rendering is the SPA's job; nothing else is
/// parsed here.
final class Markdown {

    private Markdown() {
    }

    /// The text of the first line that, trimmed, starts with `# ` — trimmed;
    /// empty when the content has no level-one heading.
    static Optional<String> firstHeading(String content) {
        if (content == null) return Optional.empty();
        for (String line : content.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("# ")) {
                return Optional.of(trimmed.substring(2).strip());
            }
        }
        return Optional.empty();
    }
}
