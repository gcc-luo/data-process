package com.example.governance.workflow;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlowExpressionTest {
    private static Object eval(String expr, Map<String, Object> row) { return FlowExpression.parse(expr).eval(row); }

    @Test void arithmeticAndPrecedence() {
        assertEquals(new BigDecimal("7"), eval("1 + 2 * 3", Map.of()));
        assertEquals(new BigDecimal("9"), eval("(1 + 2) * 3", Map.of()));
        assertEquals(new BigDecimal("-2"), eval("-4 / 2", Map.of()));
    }

    @Test void fieldReferencesAreCaseInsensitive() {
        assertEquals(new BigDecimal("30"), eval("price * qty", Map.of("price", new BigDecimal("10"), "qty", new BigDecimal("3"))));
    }

    @Test void comparisonsAndLogic() {
        assertEquals(Boolean.TRUE, eval("amount > 100 AND status = 'OK'", Map.of("amount", new BigDecimal("150"), "status", "OK")));
        assertEquals(Boolean.FALSE, eval("NOT true", Map.of()));
        assertEquals(Boolean.TRUE, eval("1 != 2 OR false", Map.of()));
    }

    @Test void nullPropagates() {
        assertNull(eval("missing + 1", Map.of()));
        assertNull(eval("missing > 1", Map.of()));
        assertEquals(Boolean.FALSE, eval("missing > 1 AND true", Map.of()));
    }

    @Test void functions() {
        assertEquals("ABC", eval("UPPER(name)", Map.of("name", "abc")));
        assertEquals("abc", eval("LOWER('ABC')", Map.of()));
        assertEquals("bc", eval("SUBSTR('abc', 2)", Map.of()));
        assertEquals("b", eval("SUBSTR('abc', 2, 1)", Map.of()));
        assertEquals("ab!", eval("CONCAT('a', 'b', '!')", Map.of()));
        assertEquals(new BigDecimal("3"), eval("ABS(-3)", Map.of()));
        assertEquals(new BigDecimal("3.14"), eval("ROUND(3.14159, 2)", Map.of()));
        assertEquals("x", eval("TRIM('  x  ')", Map.of()));
    }

    @Test void stringComparisonAndConcatNull() {
        assertEquals(Boolean.TRUE, eval("'a' < 'b'", Map.of()));
        assertEquals("a", eval("CONCAT('a', missing)", Map.of()));
    }

    @Test void typeErrorsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> eval("'a' + 1", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> eval("1 AND true", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> eval("'a' > 'b' + 1", Map.of()));
    }

    @Test void syntaxErrorsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("1 +"));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("FOO(1)"));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("(1"));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse(""));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("1; DROP TABLE x"));
    }
}
