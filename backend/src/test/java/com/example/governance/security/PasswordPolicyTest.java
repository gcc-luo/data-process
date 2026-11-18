package com.example.governance.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {
    @Test void acceptsStrongPasswords() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("Str0ng-Pass!2026", "alice"));
        assertDoesNotThrow(() -> PasswordPolicy.validate("Abcdef12", "alice"));
    }
    @Test void rejectsWeakPasswords() {
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate(null, "alice"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate(" ", "alice"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("Ab1!", "alice"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("abcdef12", "alice"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("Abcdefgh", "alice"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("Abcdef12 ", "alice"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("Alice12345!", "alice"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("A".repeat(73) + "1!", "alice"));
    }
}
