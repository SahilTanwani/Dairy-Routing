package com.dairy.milkroute.controller;

/**
 * The API's base path, in one place.
 *
 * <p>Every controller mounts under {@link #V1}. Keeping it as a constant rather than
 * repeating the literal is what stops the next controller being mounted at the root by
 * accident — which is how this codebase ended up with {@code /health} versioned and
 * {@code /plans}, {@code /advisory} and {@code /admin} not.
 *
 * <p>Versioning in the path rather than a header because this API is read by curl, a demo
 * script and a driver's phone. A path anyone can see and retype beats a negotiation nobody
 * can debug from a terminal.
 */
public final class ApiPaths {

    /** Base path for version 1. Annotation values must be compile-time constants. */
    public static final String V1 = "/api/v1";

    private ApiPaths() {
    }
}
