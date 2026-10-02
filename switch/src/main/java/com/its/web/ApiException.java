package com.its.web;

import org.springframework.http.HttpStatus;

/** An error with an HTTP status and a message that is safe to return to the caller. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
