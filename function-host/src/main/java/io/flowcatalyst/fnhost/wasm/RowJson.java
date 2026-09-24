package io.flowcatalyst.fnhost.wasm;

import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/// A result set as `fc_db_query`'s answer, `{"rows":[{col:value}],"truncated":bool}`
/// (`docs/spec/function-wasm-db.md`), written **row by row** under the caps:
/// at most [#MAX_ROWS] rows or [#MAX_ROW_BYTES] bytes of row JSON, whichever
/// comes first; the row that would cross either is not written and the answer
/// says `truncated`. Nothing past the cap is converted.
///
/// SQL → JSON, by the column's PostgreSQL type:
///
/// | Type | JSON |
/// |---|---|
/// | `int2`, `int4`, `int8`, `oid` | number |
/// | `float4`, `float8` | number; `NaN`/`Infinity`/`-Infinity` as those strings (JSON has no such numbers) |
/// | `numeric` | string, exactly as PostgreSQL prints it (`"12.50"`) — never a lossy double |
/// | `bool` | boolean |
/// | `timestamptz` / `timestamp` | ISO-8601 string, with offset (always `Z`) / without; `"infinity"`/`"-infinity"` |
/// | `date`, `time`, `timetz` | ISO-8601 string |
/// | `bytea` | base64 string (standard alphabet, padded) |
/// | `json`, `jsonb` | the parsed JSON value |
/// | SQL `NULL` of any type | `null` |
/// | anything else (text, uuid, interval, arrays, …) | PostgreSQL's text form, as a string |
///
/// Keys are the column labels; two columns with the same label keep the last
/// (alias them apart).
final class RowJson {

    /// The row cap (spec: 10 000).
    static final int MAX_ROWS = 10_000;

    /// The row-JSON byte cap (spec: 8 MiB) — the rows array's content, commas
    /// included.
    static final int MAX_ROW_BYTES = 8 * 1024 * 1024;

    private static final byte[] PREFIX = "{\"rows\":[".getBytes(StandardCharsets.UTF_8);

    private RowJson() {
    }

    /// Writes the answer for `rs`, which the caller has already bounded to
    /// `maxRows + 1` rows at the server ([java.sql.Statement#setMaxRows]) —
    /// the extra row only tells a result of exactly `maxRows` from a longer one.
    static byte[] write(ResultSet rs, int maxRows, int maxRowBytes) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columns = meta.getColumnCount();
        String[] labels = new String[columns];
        Kind[] kinds = new Kind[columns];
        for (int c = 0; c < columns; c++) {
            labels[c] = meta.getColumnLabel(c + 1);
            kinds[c] = Kind.of(meta.getColumnTypeName(c + 1), meta.getColumnType(c + 1));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(PREFIX);
        int rows = 0;
        long bytes = 0;
        boolean truncated = false;
        while (rs.next()) {
            if (rows == maxRows) {
                truncated = true;
                break;
            }
            byte[] row = Json.MAPPER.writeValueAsBytes(row(rs, labels, kinds));
            long next = bytes + row.length + (rows == 0 ? 0 : 1);
            if (next > maxRowBytes) {
                truncated = true;
                break;
            }
            if (rows > 0) {
                out.write(',');
            }
            out.writeBytes(row);
            bytes = next;
            rows++;
        }
        out.writeBytes(("],\"truncated\":" + truncated + "}").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /// The answer for a statement that returned no result set.
    static byte[] empty() {
        return "{\"rows\":[],\"truncated\":false}".getBytes(StandardCharsets.UTF_8);
    }

    private static ObjectNode row(ResultSet rs, String[] labels, Kind[] kinds) throws SQLException {
        ObjectNode row = Json.MAPPER.createObjectNode();
        for (int c = 0; c < labels.length; c++) {
            put(row, labels[c], kinds[c], rs, c + 1);
        }
        return row;
    }

    private static void put(ObjectNode row, String label, Kind kind, ResultSet rs, int i) throws SQLException {
        switch (kind) {
            case INTEGER -> {
                long v = rs.getLong(i);
                if (rs.wasNull()) row.putNull(label); else row.put(label, v);
            }
            case FLOAT -> {
                double v = rs.getDouble(i);
                if (rs.wasNull()) row.putNull(label);
                else if (Double.isFinite(v)) row.put(label, v);
                else row.put(label, Double.isNaN(v) ? "NaN" : v > 0 ? "Infinity" : "-Infinity");
            }
            case BOOLEAN -> {
                boolean v = rs.getBoolean(i);
                if (rs.wasNull()) row.putNull(label); else row.put(label, v);
            }
            case TIMESTAMPTZ -> {
                OffsetDateTime v = rs.getObject(i, OffsetDateTime.class);
                if (v == null) row.putNull(label);
                else if (v.equals(OffsetDateTime.MAX)) row.put(label, "infinity");
                else if (v.equals(OffsetDateTime.MIN)) row.put(label, "-infinity");
                else row.put(label, DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(v));
            }
            case TIMESTAMP -> {
                LocalDateTime v = rs.getObject(i, LocalDateTime.class);
                if (v == null) row.putNull(label);
                else if (v.equals(LocalDateTime.MAX)) row.put(label, "infinity");
                else if (v.equals(LocalDateTime.MIN)) row.put(label, "-infinity");
                else row.put(label, DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(v));
            }
            case DATE -> {
                LocalDate v = rs.getObject(i, LocalDate.class);
                if (v == null) row.putNull(label);
                else if (v.equals(LocalDate.MAX)) row.put(label, "infinity");
                else if (v.equals(LocalDate.MIN)) row.put(label, "-infinity");
                else row.put(label, DateTimeFormatter.ISO_LOCAL_DATE.format(v));
            }
            case TIME -> {
                LocalTime v = rs.getObject(i, LocalTime.class);
                if (v == null) row.putNull(label); else row.put(label, DateTimeFormatter.ISO_LOCAL_TIME.format(v));
            }
            case TIMETZ -> {
                OffsetTime v = rs.getObject(i, OffsetTime.class);
                if (v == null) row.putNull(label); else row.put(label, DateTimeFormatter.ISO_OFFSET_TIME.format(v));
            }
            case BYTES -> {
                byte[] v = rs.getBytes(i);
                if (v == null) row.putNull(label); else row.put(label, Base64.getEncoder().encodeToString(v));
            }
            case JSON -> {
                String v = rs.getString(i);
                if (v == null) {
                    row.putNull(label);
                } else {
                    try {
                        row.set(label, Json.MAPPER.readTree(v));
                    } catch (JacksonException e) {
                        row.put(label, v); // the server's own json output always parses; never lose the value
                    }
                }
            }
            case TEXT -> {
                String v = rs.getString(i);
                if (v == null) row.putNull(label); else row.put(label, v);
            }
        }
    }

    /// How one column converts, decided once per result set.
    enum Kind {
        INTEGER, FLOAT, BOOLEAN, TIMESTAMPTZ, TIMESTAMP, DATE, TIME, TIMETZ, BYTES, JSON, TEXT;

        static Kind of(String typeName, int jdbcType) {
            String name = typeName == null ? "" : typeName.toLowerCase(Locale.ROOT);
            return switch (name) {
                case "int2", "int4", "int8", "oid", "serial", "bigserial", "smallserial" -> INTEGER;
                case "float4", "float8" -> FLOAT;
                case "numeric" -> TEXT; // exact, as PostgreSQL prints it
                case "bool" -> BOOLEAN;
                case "timestamptz" -> TIMESTAMPTZ;
                case "timestamp" -> TIMESTAMP;
                case "date" -> DATE;
                case "time" -> TIME;
                case "timetz" -> TIMETZ;
                case "bytea" -> BYTES;
                case "json", "jsonb" -> JSON;
                default -> switch (jdbcType) {
                    case Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.TINYINT -> INTEGER;
                    case Types.REAL, Types.DOUBLE, Types.FLOAT -> FLOAT;
                    case Types.BOOLEAN -> BOOLEAN;
                    case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> BYTES;
                    default -> TEXT;
                };
            };
        }
    }
}
