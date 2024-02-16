package de.telekom.eni.pandora.horizon.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@ToString
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class EventMessage implements Serializable, IdentifiableMessage {

    @Serial
    private static final long serialVersionUID = 800L;

    protected String uuid;

    protected String environment;

    protected Map<String, Object> additionalFields;

    protected Event event;

    protected Status status;

    protected Map<String, List<String>> httpHeaders;

    protected EventMessage(Event event, String environment) {
        this(event, environment, null, null);
    }

    protected EventMessage(Event event, String environment, Map<String, Object> additionalFields, Map<String,
            List<String>> httpHeaders) {
        this.event = event;
        this.environment = environment;
        this.additionalFields = additionalFields;
        this.httpHeaders = httpHeaders;
        this.uuid = UUID.randomUUID().toString();
    }
}
