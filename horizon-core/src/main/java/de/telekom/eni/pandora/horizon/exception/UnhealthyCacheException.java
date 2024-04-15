package de.telekom.eni.pandora.horizon.exception;

import de.telekom.eni.pandora.horizon.common.exception.HorizonException;

public class UnhealthyCacheException extends HorizonException {

    public UnhealthyCacheException(String message, Throwable e) {
        super(message, e);
    }

    public UnhealthyCacheException(String message) {
        super(message);
    }
}
