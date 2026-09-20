package fixture.typical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.flowcatalyst.function.Function;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.function.Result;

import java.util.Set;

/// Bench fixture — "typical" (docs/spec/function-host-benchmark.md B1): shades
/// classic jackson-databind + networknt's json-schema-validator, plus
/// function-api as `provided` — the realistic-worst-case-for-metaspace
/// fixture (many more classes loaded per version than "lean"). `init` builds
/// the ObjectMapper and compiles the schema once; `handle` parses/validates a
/// small JSON body against it.
public final class TypicalFn implements Function {

    private static final String SCHEMA_JSON = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "required": ["name", "amount"],
              "properties": {
                "name": {"type": "string", "minLength": 1, "maxLength": 200},
                "amount": {"type": "number", "minimum": 0},
                "note": {"type": "string", "maxLength": 2000}
              },
              "additionalProperties": false
            }
            """;

    private ObjectMapper mapper;
    private JsonSchema schema;

    @Override
    public void init(FunctionContext ctx) throws Exception {
        mapper = new ObjectMapper();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        schema = factory.getSchema(SCHEMA_JSON);
    }

    @Override
    public Result handle(Request in, FunctionContext ctx) throws Exception {
        byte[] body = in.body();
        if (body.length == 0) {
            return Result.fail("EMPTY_BODY");
        }
        JsonNode node;
        try {
            node = mapper.readTree(body);
        } catch (Exception e) {
            return Result.fail("INVALID_JSON");
        }
        Set<ValidationMessage> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            return Result.fail("SCHEMA_INVALID");
        }
        return Result.ack();
    }
}
