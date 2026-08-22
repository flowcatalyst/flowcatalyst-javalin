---
name: Jackson version constraints
description: Jackson 3 has broken split-package design - stay on Jackson 2.19+ to satisfy Flyway 12's transitive Jackson 3 dependency
type: feedback
---

Stay on Jackson 2.19+ (com.fasterxml.jackson namespace). Do NOT upgrade to Jackson 3 (tools.jackson namespace).

**Why:** Jackson 3 has a broken split-package design where annotations and core exceptions stay in `com.fasterxml.jackson` while databind moved to `tools.jackson`. This creates an unmaintainable mess. Flyway 12 transitively depends on Jackson 3 databind which needs `com.fasterxml.jackson.annotation.JsonSerializeAs` — available in jackson-annotations 2.19+.

**How to apply:** When updating Jackson, stay in the 2.19+ range. The version must be high enough for Flyway 12 compatibility.
