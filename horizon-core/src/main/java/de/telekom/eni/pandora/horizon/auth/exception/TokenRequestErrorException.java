package de.telekom.eni.pandora.horizon.auth.exception;

import de.telekom.eni.pandora.horizon.common.exception.HorizonException;

public class TokenRequestErrorException extends HorizonException {
    private static final String ERROR_MSG_FORMAT = "Can't receive access token. Response: %s";

    private TokenRequestErrorException(final String message) {
        super(message);
    }

    public static TokenRequestErrorException of(final String responseAsString) {
        final var message = String.format(ERROR_MSG_FORMAT, responseAsString);
        return new TokenRequestErrorException(message);
    }
}
