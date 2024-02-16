package de.telekom.eni.pandora.horizon.model.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Serial;
import java.io.Serializable;

@AllArgsConstructor
@Getter
public enum DeliveryType implements Serializable {

    CALLBACK("callback"),
    SERVER_SENT_EVENT("sse");

    private final String value;

    @Serial
    private static final long serialVersionUID = 400L;
    public static DeliveryType fromString(String string) {
        return switch (string.toLowerCase()) {
            case "callback" -> CALLBACK;
            case "sse", "server_sent_event" -> SERVER_SENT_EVENT;
            default -> throw new RuntimeException(String.format("Invalid delivery type: %s", string));
        };
    }

}
