package de.telekom.eni.pandora.horizon.model.meta;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * Contains the information about the last health check made by the plunger.
 */
@Data
@AllArgsConstructor()
public class CircuitBreakerHealthCheck implements Serializable {
    @Serial
    private static final long serialVersionUID = 200L;
    private final Date firstCheckedDate;
    private final Date lastCheckedDate;
    private final int returnCode;
    private final String reasonPhrase;
}
