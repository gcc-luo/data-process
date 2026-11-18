package com.example.governance.security;

import java.nio.charset.StandardCharsets;

/** Enterprise password baseline applied wherever a password is set (create, reset, self-change, bootstrap). */
public final class PasswordPolicy {
    public static final String RULE_TEXT = "密码至少 8 位，需包含大写字母、小写字母、数字、特殊字符中的至少三类，不能含空白或账号名";

    private PasswordPolicy() {}

    public static void validate(String password, String username) {
        if (password == null || password.isBlank()) throw new IllegalArgumentException("密码不能为空");
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw new IllegalArgumentException("密码不能超过 72 字节");
        if (password.length() < 8) throw new IllegalArgumentException("密码至少 8 位");
        if (password.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException("密码不能包含空白字符");
        int classes = 0;
        if (password.chars().anyMatch(Character::isLowerCase)) classes++;
        if (password.chars().anyMatch(Character::isUpperCase)) classes++;
        if (password.chars().anyMatch(Character::isDigit)) classes++;
        if (password.chars().anyMatch(character -> !Character.isLetterOrDigit(character) && !Character.isWhitespace(character))) classes++;
        if (classes < 3) throw new IllegalArgumentException("密码需包含大写字母、小写字母、数字、特殊字符中的至少三类");
        if (username != null && !username.isBlank() && password.toLowerCase().contains(username.toLowerCase())) throw new IllegalArgumentException("密码不能包含账号名");
    }
}
