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

    @Test void concatCoercesNumbersToPlainString() {
        assertEquals("t1", eval("CONCAT('t', id)", Map.of("id", new BigDecimal("1"))));
        assertEquals("v10.50", eval("CONCAT('v', amount)", Map.of("amount", new BigDecimal("10.50"))));
        assertThrows(IllegalArgumentException.class, () -> eval("CONCAT('a', flag)", Map.of("flag", Boolean.TRUE)));
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

    @Test void divisionByZeroIsNull() {
        assertNull(eval("1 / 0", Map.of()));
    }

    @Test void arityCheckedAtParseTime() {
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("UPPER('a', 'b')"));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("ROUND()"));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("CONCAT()"));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("SUBSTR('a')"));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("SUBSTR('a', 1, 2, 3)"));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("ABS(1, 2)"));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("ROUND(1, 2, 3)"));
    }

    @Test void malformedNumberRejectedAtParseTime() {
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("1.2.3"));
    }

    @Test void substrBounds() {
        assertThrows(IllegalArgumentException.class, () -> eval("SUBSTR('abc', 0)", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> eval("SUBSTR('abc', 1, -1)", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> eval("SUBSTR('abc', 1, 3000000000)", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> eval("SUBSTR('abc', 3000000000)", Map.of()));
    }

    @Test void roundDigitsRange() {
        assertEquals(new BigDecimal("2"), eval("ROUND(1.5)", Map.of()));
        assertEquals(new BigDecimal("1.50"), eval("ROUND(1.5, 2)", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> eval("ROUND(1.5, 19)", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> eval("ROUND(1.5, -19)", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> eval("ROUND(1.5, 1000000000)", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> eval("ROUND(1.5, 3000000000)", Map.of()));
    }

    @Test void functionNamesCaseInsensitiveAndNested() {
        assertEquals("ABC", eval("upper(name)", Map.of("name", "abc")));
        assertEquals("ABC", eval("UPPER(TRIM(name))", Map.of("name", " abc ")));
    }

    @Test void logicNullSemanticsAndBooleanEquality() {
        assertEquals(Boolean.FALSE, eval("missing > 1 OR false", Map.of()));
        assertNull(eval("NOT null", Map.of()));
        assertEquals(Boolean.TRUE, eval("true = true", Map.of()));
        assertEquals(Boolean.TRUE, eval("true != false", Map.of()));
        assertEquals(Boolean.FALSE, eval("false = true", Map.of()));
    }

    @Test void shortCircuitGuards() {
        assertEquals(Boolean.FALSE, eval("false AND 'a' + 1", Map.of()));
        assertEquals(Boolean.TRUE, eval("true OR 'a' + 1", Map.of()));
    }

    @Test void inputLimits() {
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("(".repeat(5000) + "1" + ")".repeat(5000)));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("(".repeat(300) + "1" + ")".repeat(300)));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("-".repeat(3000) + "1"));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("NOT ".repeat(1000) + "true"));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("1 + ".repeat(2000) + "1"));
    }
}
