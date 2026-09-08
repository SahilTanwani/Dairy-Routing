package com.dairy.milkroute.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** No plan with that id. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class PlanNotFoundException extends RuntimeException {

    public PlanNotFoundException(String message) {
        super(message);
    }
}
