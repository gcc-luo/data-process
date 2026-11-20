package com.example.governance.workflow;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Restricted expression language for FIELD_FILTER / FIELD_CALC. No arbitrary code execution. */
public final class FlowExpression {
    public interface Expr { Object eval(Map<String, Object> row); }
    private static final Set<String> FUNCTIONS = Set.of("UPPER", "LOWER", "TRIM", "SUBSTR", "CONCAT", "ABS", "ROUND");
    private record Token(String kind, String text) {}
    private final List<Token> tokens;
    private int pos;
    private FlowExpression(List<Token> tokens) { this.tokens = tokens; }

    public static Expr parse(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("表达式不能为空");
        var parser = new FlowExpression(tokenize(source));
        Expr expr = parser.or();
        if (parser.pos != parser.tokens.size()) throw new IllegalArgumentException("表达式语法错误: 多余内容 " + parser.tokens.get(parser.pos).text());
        return expr;
    }

    private static List<Token> tokenize(String source) {
        var tokens = new ArrayList<Token>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '\'') {
                int end = source.indexOf('\'', i + 1);
                if (end < 0) throw new IllegalArgumentException("表达式语法错误: 字符串未闭合");
                tokens.add(new Token("STRING", source.substring(i + 1, end))); i = end + 1; continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < source.length() && Character.isDigit(source.charAt(i + 1)))) {
                int end = i;
                while (end < source.length() && (Character.isDigit(source.charAt(end)) || source.charAt(end) == '.')) end++;
                tokens.add(new Token("NUMBER", source.substring(i, end))); i = end; continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int end = i;
                while (end < source.length() && (Character.isLetterOrDigit(source.charAt(end)) || source.charAt(end) == '_')) end++;
                tokens.add(new Token("IDENT", source.substring(i, end))); i = end; continue;
            }
            if (c == '(' || c == ')' || c == ',') { tokens.add(new Token(String.valueOf(c), String.valueOf(c))); i++; continue; }
            if (c == '!' && i + 1 < source.length() && source.charAt(i + 1) == '=') { tokens.add(new Token("OP", "!=")); i += 2; continue; }
            if (c == '>' || c == '<') {
                if (i + 1 < source.length() && source.charAt(i + 1) == '=') { tokens.add(new Token("OP", c + "=")); i += 2; }
                else { tokens.add(new Token("OP", String.valueOf(c))); i++; }
                continue;
            }
            if (c == '=' || c == '+' || c == '-' || c == '*' || c == '/') { tokens.add(new Token("OP", String.valueOf(c))); i++; continue; }
            throw new IllegalArgumentException("表达式语法错误: 非法字符 " + c);
        }
        return tokens;
    }

    private Token peek() { return pos < tokens.size() ? tokens.get(pos) : null; }
    private Token take() { Token t = peek(); if (t == null) throw new IllegalArgumentException("表达式语法错误: 意外结束"); pos++; return t; }
    private boolean matchOp(String op) { Token t = peek(); if (t != null && t.kind().equals("OP") && t.text().equals(op)) { pos++; return true; } return false; }
    private boolean matchKind(String kind) { Token t = peek(); if (t != null && t.kind().equals(kind)) { pos++; return true; } return false; }
    private boolean matchIdent(String word) { Token t = peek(); if (t != null && t.kind().equals("IDENT") && t.text().equalsIgnoreCase(word)) { pos++; return true; } return false; }

    private Expr or() {
        Expr left = and();
        while (matchIdent("OR")) { Expr right = and(); Expr l = left; left = row -> { Object a = l.eval(row); Object b = right.eval(row); return boolOrFalse(a) || boolOrFalse(b); }; }
        return left;
    }
    private Expr and() {
        Expr left = not();
        while (matchIdent("AND")) { Expr right = not(); Expr l = left; left = row -> { Object a = l.eval(row); Object b = right.eval(row); return boolOrFalse(a) && boolOrFalse(b); }; }
        return left;
    }
    private Expr not() {
        if (matchIdent("NOT")) { Expr inner = not(); return row -> { Object v = inner.eval(row); return v == null ? null : !bool(v); }; }
        return comparison();
    }
    private Expr comparison() {
        Expr left = additive();
        Token t = peek();
        if (t != null && t.kind().equals("OP") && Set.of("=", "!=", ">", "<", ">=", "<=").contains(t.text())) {
            pos++;
            Expr right = additive();
            String op = t.text();
            return row -> {
                Object a = left.eval(row); Object b = right.eval(row);
                if (a == null || b == null) return null;
                int cmp;
                if (a instanceof BigDecimal x && b instanceof BigDecimal y) cmp = x.compareTo(y);
                else if (a instanceof String x && b instanceof String y) cmp = x.compareTo(y);
                else if (a instanceof Boolean x && b instanceof Boolean y && (op.equals("=") || op.equals("!="))) cmp = x.equals(y) ? 0 : 1;
                else throw new IllegalArgumentException("表达式类型错误: 无法比较 " + a.getClass().getSimpleName() + " 与 " + b.getClass().getSimpleName());
                return switch (op) { case "=" -> cmp == 0; case "!=" -> cmp != 0; case ">" -> cmp > 0; case "<" -> cmp < 0; case ">=" -> cmp >= 0; default -> cmp <= 0; };
            };
        }
        return left;
    }
    private Expr additive() {
        Expr left = multiplicative();
        while (true) {
            if (matchOp("+")) { Expr right = multiplicative(); Expr l = left; left = row -> arith(l.eval(row), right.eval(row), "+"); }
            else if (matchOp("-")) { Expr right = multiplicative(); Expr l = left; left = row -> arith(l.eval(row), right.eval(row), "-"); }
            else return left;
        }
    }
    private Expr multiplicative() {
        Expr left = unary();
        while (true) {
            if (matchOp("*")) { Expr right = unary(); Expr l = left; left = row -> arith(l.eval(row), right.eval(row), "*"); }
            else if (matchOp("/")) { Expr right = unary(); Expr l = left; left = row -> arith(l.eval(row), right.eval(row), "/"); }
            else return left;
        }
    }
    private Expr unary() {
        if (matchOp("-")) { Expr inner = unary(); return row -> { Object v = inner.eval(row); return v == null ? null : num(v).negate(); }; }
        if (matchOp("+")) return unary();
        return primary();
    }
    private Expr primary() {
        Token t = take();
        if (t.kind().equals("NUMBER")) return row -> new BigDecimal(t.text());
        if (t.kind().equals("STRING")) return row -> t.text();
        if (t.kind().equals("(")) { Expr inner = or(); expect(")"); return inner; }
        if (t.kind().equals("IDENT")) {
            String word = t.text();
            if (word.equalsIgnoreCase("true")) return row -> Boolean.TRUE;
            if (word.equalsIgnoreCase("false")) return row -> Boolean.FALSE;
            if (word.equalsIgnoreCase("null")) return row -> null;
            Token next = peek();
            if (next != null && next.kind().equals("(")) {
                if (!FUNCTIONS.contains(word.toUpperCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("表达式语法错误: 未知函数 " + word);
                pos++;
                var args = new ArrayList<Expr>();
                if (!matchKind(")")) {
                    args.add(or());
                    while (matchKind(",")) args.add(or());
                    expect(")");
                }
                return call(word.toUpperCase(java.util.Locale.ROOT), args);
            }
            String field = word.toLowerCase(java.util.Locale.ROOT);
            return row -> row.get(field);
        }
        throw new IllegalArgumentException("表达式语法错误: 意外符号 " + t.text());
    }
    private void expect(String kind) { Token t = take(); if (!t.kind().equals(kind)) throw new IllegalArgumentException("表达式语法错误: 期望 " + kind); }

    private static Expr call(String fn, List<Expr> args) {
        return row -> {
            var values = new ArrayList<Object>();
            for (Expr arg : args) values.add(arg.eval(row));
            switch (fn) {
                case "UPPER": case "LOWER": case "TRIM": {
                    requireArity(fn, values, 1);
                    Object v = values.get(0);
                    if (v == null) return null;
                    String s = str(v);
                    return fn.equals("UPPER") ? s.toUpperCase(java.util.Locale.ROOT) : fn.equals("LOWER") ? s.toLowerCase(java.util.Locale.ROOT) : s.trim();
                }
                case "SUBSTR": {
                    if (values.size() < 2 || values.size() > 3) throw new IllegalArgumentException("表达式参数错误: SUBSTR 需 2-3 参");
                    Object v = values.get(0);
                    if (v == null) return null;
                    String s = str(v);
                    int start = num(values.get(1)).intValue();
                    if (start < 1) throw new IllegalArgumentException("表达式参数错误: SUBSTR 起始需 >=1");
                    int from = Math.min(start - 1, s.length());
                    int to = values.size() == 3 ? Math.min(from + num(values.get(2)).intValue(), s.length()) : s.length();
                    return s.substring(from, Math.max(from, to));
                }
                case "CONCAT": {
                    var sb = new StringBuilder();
                    for (Object v : values) sb.append(v == null ? "" : str(v));
                    return sb.toString();
                }
                case "ABS": {
                    requireArity(fn, values, 1);
                    Object v = values.get(0);
                    return v == null ? null : num(v).abs();
                }
                case "ROUND": {
                    if (values.isEmpty() || values.size() > 2) throw new IllegalArgumentException("表达式参数错误: ROUND 需 1-2 参");
                    Object v = values.get(0);
                    if (v == null) return null;
                    int digits = values.size() == 2 ? num(values.get(1)).intValue() : 0;
                    return num(v).setScale(digits, RoundingMode.HALF_UP);
                }
                default: throw new IllegalArgumentException("表达式语法错误: 未知函数 " + fn);
            }
        };
    }
    private static void requireArity(String fn, List<Object> values, int n) {
        if (values.size() != n) throw new IllegalArgumentException("表达式参数错误: " + fn + " 需 " + n + " 参");
    }
    private static Object arith(Object a, Object b, String op) {
        if (a == null || b == null) return null;
        BigDecimal x = num(a); BigDecimal y = num(b);
        return switch (op) {
            case "+" -> x.add(y);
            case "-" -> x.subtract(y);
            case "*" -> x.multiply(y);
            default -> y.signum() == 0 ? null : x.divide(y, MathContext.DECIMAL64);
        };
    }
    private static BigDecimal num(Object v) {
        if (v instanceof BigDecimal d) return d;
        throw new IllegalArgumentException("表达式类型错误: 期望数字, 实际 " + v.getClass().getSimpleName());
    }
    private static String str(Object v) {
        if (v instanceof String s) return s;
        throw new IllegalArgumentException("表达式类型错误: 期望字符串, 实际 " + v.getClass().getSimpleName());
    }
    /** AND/OR 将 null 视为 false（过滤语义）；非 null 操作数仍严格校验布尔类型。 */
    private static boolean boolOrFalse(Object v) { return v != null && bool(v); }
    private static boolean bool(Object v) {
        if (v instanceof Boolean b) return b;
        throw new IllegalArgumentException("表达式类型错误: 期望布尔, 实际 " + v.getClass().getSimpleName());
    }
}
