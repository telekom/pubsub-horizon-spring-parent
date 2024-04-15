package de.telekom.eni.pandora.horizon.exception;

import de.telekom.eni.pandora.horizon.common.exception.HorizonException;

public class CouldNotStartInformerException extends HorizonException {

    public CouldNotStartInformerException(String message, Throwable e) {
        super(message, e);
    }
}
