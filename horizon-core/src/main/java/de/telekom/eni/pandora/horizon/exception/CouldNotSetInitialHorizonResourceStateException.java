package de.telekom.eni.pandora.horizon.exception;

import de.telekom.eni.pandora.horizon.common.exception.HorizonException;

public class CouldNotSetInitialHorizonResourceStateException extends HorizonException {

    public CouldNotSetInitialHorizonResourceStateException(String message, Throwable e) {
        super(message, e);
    }
}
