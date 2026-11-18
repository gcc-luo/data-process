package com.example.governance.organization;

import java.util.ArrayList;
import java.util.List;

/** Minimal quoted-CSV support for admin import/export; callers add the BOM for Excel compatibility. */
public final class CsvSupport {
    private CsvSupport() {}

    public static List<String[]> parse(String text) {
        List<String[]> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else quoted = false;
                } else field.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { fields.add(field.toString()); field.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                fields.add(field.toString()); field.setLength(0);
                if (fields.stream().anyMatch(value -> !value.isBlank())) rows.add(fields.toArray(new String[0]));
                fields = new ArrayList<>();
            } else field.append(c);
        }
        fields.add(field.toString());
        if (fields.stream().anyMatch(value -> !value.isBlank())) rows.add(fields.toArray(new String[0]));
        return rows;
    }

    public static String write(List<String[]> rows) {
        StringBuilder out = new StringBuilder();
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) out.append(',');
                String value = row[i] == null ? "" : row[i];
                if (value.contains(",") || value.contains("\"") || value.contains("\n")) out.append('"').append(value.replace("\"", "\"\"")).append('"');
                else out.append(value);
            }
            out.append("\r\n");
        }
        return out.toString();
    }
}
