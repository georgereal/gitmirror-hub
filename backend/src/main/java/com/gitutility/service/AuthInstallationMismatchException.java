package com.gitutility.service;

/**
 * The bound GitHub/GHES credential cannot access this repository (transfer, uninstall, or wrong org).
 * Fail closed — do not silently switch credentials.
 */
public class AuthInstallationMismatchException extends IllegalStateException {

    public static final String CODE = "AUTH_INSTALLATION_MISMATCH";

    public AuthInstallationMismatchException(String message) {
        super(CODE + ": " + message);
    }
}
