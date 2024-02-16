package de.telekom.eni.pandora.horizon.common.exception;

public abstract class HorizonException extends Exception {

    protected HorizonException(String message, Throwable e) {
        super(message, e);
    }

    protected HorizonException(String message) {
        super(message);
    }
}
