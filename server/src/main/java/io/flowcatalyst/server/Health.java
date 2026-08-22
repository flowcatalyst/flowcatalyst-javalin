package io.flowcatalyst.server;

import io.flowcatalyst.platform.shared.json.Json;
import io.javalin.http.Context;

import java.util.LinkedHashMap;

/// `GET /health` → `{"status":"UP","version":"<version>"}` + `\n`, on both
/// listeners — the shape monitoring tooling scrapes for fc-server and fcdev alike.
final class Health {

    private Health() {}

    static void handle(Context ctx) {
        var body = new LinkedHashMap<String, String>();
        body.put("status", "UP");
        body.put("version", Version.current());
        ctx.contentType("application/json").result(Json.writeLine(body));
    }
}
