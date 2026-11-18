package com.example.governance.security;

/** Raised when an operator attempts to delegate a permission they do not hold. */
public final class PermissionGrantDeniedException extends RuntimeException {
    public PermissionGrantDeniedException(String message) { super(message); }
}
