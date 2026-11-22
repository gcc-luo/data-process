package com.example.governance.workflow;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Named SQL parameter support: {@code :name} placeholders become JDBC {@code ?} bindings.
 * Values are never string-concatenated into SQL. Quote/comment aware; PostgreSQL {@code ::} casts are skipped.
 */
public final class SqlParameters {
    public record Parsed(String jdbcSql, List<String> names) {}

    private SqlParameters() {}

    public static Parsed parse(String sql) {
        if (sql == null) throw new IllegalArgumentException("SQL 不能为空");
        var out = new StringBuilder(sql.length());
        var names = new ArrayList<String>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = sql.indexOf(c, i + 1);
                if (end < 0) { out.append(sql, i, sql.length()); break; }
                out.append(sql, i, end + 1); i = end + 1; continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                if (end < 0) { out.append(sql, i, sql.length()); break; }
                out.append(sql, i, end); i = end; continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) { out.append(sql, i, sql.length()); break; }
                out.append(sql, i, end + 2); i = end + 2; continue;
            }
            if (c == ':' && i + 1 < sql.length() && sql.charAt(i + 1) != ':'
                    && (i == 0 || sql.charAt(i - 1) != ':')
                    && isIdentStart(sql.charAt(i + 1))) {
                int j = i + 1;
                while (j < sql.length() && isIdentPart(sql.charAt(j))) j++;
                names.add(sql.substring(i + 1, j));
                out.append('?');
                i = j; continue;
            }
            out.append(c); i++;
        }
        return new Parsed(out.toString(), List.copyOf(names));
    }

    private static boolean isIdentStart(char c) { return Character.isLetter(c) || c == '_'; }
    private static boolean isIdentPart(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

    /** First meaningful keyword of a statement, upper-cased; empty when none. */
    public static String firstKeyword(String sql) {
        var parsed = parse(sql).jdbcSql().trim();
        int i = 0;
        while (i < parsed.length() && (Character.isWhitespace(parsed.charAt(i)) || parsed.charAt(i) == '(')) i++;
        int j = i;
        while (j < parsed.length() && Character.isLetter(parsed.charAt(j))) j++;
        return parsed.substring(i, j).toUpperCase(java.util.Locale.ROOT);
    }

    /** Reject multi-statement text: a second statement after the first terminating semicolon. */
    public static boolean hasMultipleStatements(String sql) {
        var jdbc = parse(sql).jdbcSql();
        boolean inSingle = false, inDouble = false, inBacktick = false, inLine = false, inBlock = false;
        boolean seenContent = false;
        for (int i = 0; i < jdbc.length(); i++) {
            char c = jdbc.charAt(i);
            if (inLine) { if (c == '\n') inLine = false; continue; }
            if (inBlock) { if (c == '*' && i + 1 < jdbc.length() && jdbc.charAt(i + 1) == '/') { inBlock = false; i++; } continue; }
            if (inSingle) { if (c == '\'') inSingle = false; continue; }
            if (inDouble) { if (c == '"') inDouble = false; continue; }
            if (inBacktick) { if (c == '`') inBacktick = false; continue; }
            if (c == '\'') { inSingle = true; seenContent = true; continue; }
            if (c == '"') { inDouble = true; seenContent = true; continue; }
            if (c == '`') { inBacktick = true; seenContent = true; continue; }
            if (c == '-' && i + 1 < jdbc.length() && jdbc.charAt(i + 1) == '-') { inLine = true; continue; }
            if (c == '/' && i + 1 < jdbc.length() && jdbc.charAt(i + 1) == '*') { inBlock = true; continue; }
            if (c == ';') {
                if (!seenContent) continue;
                for (int k = i + 1; k < jdbc.length(); k++) {
                    char n = jdbc.charAt(k);
                    if (Character.isWhitespace(n) || n == ';') continue;
                    if (n == '-' && k + 1 < jdbc.length() && jdbc.charAt(k + 1) == '-') break;
                    if (n == '/' && k + 1 < jdbc.length() && jdbc.charAt(k + 1) == '*') break;
                    return true;
                }
                return false;
            }
            if (!Character.isWhitespace(c)) seenContent = true;
        }
        return false;
    }

    public static Object convert(String type, Object raw, String name) {
        if (raw == null) return null;
        String text = raw instanceof String s ? s : String.valueOf(raw);
        try {
            return switch (type == null ? "STRING" : type) {
                case "STRING" -> text;
                case "NUMBER" -> new BigDecimal(text);
                case "BOOLEAN" -> {
                    if (raw instanceof Boolean b) yield b;
                    if ("true".equalsIgnoreCase(text) || "1".equals(text)) yield Boolean.TRUE;
                    if ("false".equalsIgnoreCase(text) || "0".equals(text)) yield Boolean.FALSE;
                    throw new IllegalArgumentException("参数 " + name + " 不是合法布尔值");
                }
                case "DATE" -> java.sql.Date.valueOf(LocalDate.parse(text));
                case "DATETIME" -> {
                    String normalized = text.trim().replace(' ', 'T');
                    try { yield java.sql.Timestamp.valueOf(java.time.LocalDateTime.parse(normalized)); }
                    catch (DateTimeParseException ex) { yield java.sql.Timestamp.from(OffsetDateTime.parse(normalized).toInstant()); }
                }
                default -> throw new IllegalArgumentException("参数 " + name + " 类型不支持: " + type);
            };
        } catch (DateTimeParseException | NumberFormatException ex) {
            throw new IllegalArgumentException("参数 " + name + " 的值无法转换为 " + type);
        }
    }

    /** Bind parsed placeholder values in occurrence order from a variable value map. */
    public static void bind(PreparedStatement statement, Parsed parsed, Map<String, Object> values, Map<String, String> types) throws Exception {
        int pos = 1;
        for (String name : parsed.names()) {
            if (!values.containsKey(name)) throw new IllegalArgumentException("缺少参数值: " + name);
            statement.setObject(pos++, convert(types.get(name), values.get(name), name));
        }
    }
}