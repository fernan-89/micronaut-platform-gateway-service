package com.thinklab.domain.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessExceptionGuardsTest {

    private static class TestException extends BusinessException {
        TestException(String code, String message) {
            super(code, message);
        }

        TestException(String code, String message, Throwable cause) {
            super(code, message, cause);
        }
    }

    @Test
    @DisplayName("BusinessException validates code, message and cause")
    void guards() {
        Throwable cause = new IllegalStateException("root");
        TestException withCause = new TestException("ERR-X", "boom", cause);
        assertEquals("ERR-X", withCause.getErrorCode());
        assertEquals(cause, withCause.getCause());
        assertThrows(NullPointerException.class, () -> new TestException("ERR-X", "boom", null));
        assertThrows(IllegalArgumentException.class, () -> new TestException(" ", "boom"));
        assertThrows(IllegalArgumentException.class, () -> new TestException("ERR-X", " "));
        assertThrows(NullPointerException.class, () -> new TestException(null, "boom"));
        assertThrows(NullPointerException.class, () -> new TestException("ERR-X", null));
    }
}