package com.its.iso;

/** The message isn't a valid document of the expected ISO 20022 type. */
public class IsoFormatException extends RuntimeException {

    public IsoFormatException(String message) {
        super(message);
    }

    public IsoFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
