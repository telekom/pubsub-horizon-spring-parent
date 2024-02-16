package de.telekom.eni.pandora.horizon.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PublishedEventMessage extends EventMessage {

    public PublishedEventMessage(Event event, String environment) {
        this(event, environment, null, null);
    }

    public PublishedEventMessage(Event event, String environment, Map<String, Object> additionalFields, Map<String,
            List<String>> httpHeaders) {
        super(event, environment, additionalFields, httpHeaders);
    }
}
