package io.flowcatalyst.router.wire;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/// How a message is to be delivered. Only HTTP exists.
///
/// Modelled as a sealed type rather than an enum so an unrecognised value
/// survives deserialisation: the contract is that an unsupported type is
/// **ACK-dropped with a diagnostic** (`docs/spec/router.md` §6.5), which
/// needs the offending string, and an enum would have thrown at parse time
/// and turned a drop into a poison message.
public sealed interface MediationType {

    MediationType HTTP = new Http();

    /// The only supported type.
    record Http() implements MediationType {
        @Override
        public String wireValue() {
            return "HTTP";
        }
    }

    /// Any other value. Carried so the ACK-drop can name what it dropped.
    record Unsupported(String raw) implements MediationType {
        @Override
        public String wireValue() {
            return raw;
        }
    }

    @JsonValue
    String wireValue();

    @JsonCreator
    static MediationType parse(String raw) {
        return "HTTP".equals(raw) ? HTTP : new Unsupported(raw == null ? "" : raw);
    }
}
