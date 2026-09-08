package com.dairy.milkroute.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * An unrecognised farmer code.
 *
 * <p>The message is deliberately generic and identical whatever was asked for. This endpoint
 * needs no authentication to be useful to a farmer with a feature phone, which also means
 * anyone can probe it; distinguishing "no such code" from "that code exists but not for you"
 * would turn it into a way of enumerating the cooperative's membership.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class FarmerNotFoundException extends RuntimeException {

    public FarmerNotFoundException() {
        super("no collection record found for that code");
    }
}
