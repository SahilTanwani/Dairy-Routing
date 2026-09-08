package com.dairy.milkroute.error;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.domain.trip.IllegalTransitionException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns domain failures into answers a caller can act on.
 *
 * <p>Exists mainly for the exceptions thrown out of {@code domain/}, which carry no Spring
 * annotations and would otherwise surface as 500s. A driver's phone getting a 500 will retry
 * forever; a 409 tells it the event is not going to be accepted however many times it asks.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ClockProvider clock;

    public GlobalExceptionHandler(ClockProvider clock) {
        this.clock = clock;
    }

    @ExceptionHandler(IllegalTransitionException.class)
    public ResponseEntity<Map<String, Object>> illegalTransition(IllegalTransitionException e) {
        return body(HttpStatus.CONFLICT, e.getMessage(), Map.of(
                "from", String.valueOf(e.from()),
                "to", String.valueOf(e.to())));
    }

    /**
     * Bad litres, an unknown event type, and anything else the domain refuses on its own
     * terms. A 400 with the reason beats a stack trace.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage(), Map.of());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status,
                                                     String message,
                                                     Map<String, Object> extra) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        // From the clock provider, not the wall clock: hard rule 1 has no exceptions, and
        // under the sim profile an error should carry simulated time like everything else.
        payload.put("timestamp", clock.now().toString());
        payload.put("status", status.value());
        payload.put("error", status.getReasonPhrase());
        payload.put("message", message);
        payload.putAll(extra);
        return ResponseEntity.status(status).body(payload);
    }
}
