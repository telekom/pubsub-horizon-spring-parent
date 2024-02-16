package de.telekom.eni.pandora.horizon.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.telekom.eni.pandora.horizon.model.db.PartialEvent;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Map;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@AllArgsConstructor
@NoArgsConstructor
public class StatusMessage implements Serializable, IdentifiableMessage {

    private String uuid;

    private PartialEvent event;

    private String subscriptionId;

    private Status status;

    private Map<String, Object> additionalFields;

    private String errorType;

    private String errorMessage;
    private DeliveryType deliveryType;

    public StatusMessage(String uuid, String eventId, Status status, DeliveryType deliveryType) {
        this.uuid = uuid;
        this.event = new PartialEvent(eventId, null);
        this.status = status;
    }

    public StatusMessage(String uuid, PartialEvent event, String subscriptionId, Status status, DeliveryType deliveryType) {
        this.uuid = uuid;
        this.event = event;
        this.subscriptionId = subscriptionId;
        this.status = status;
        this.deliveryType = deliveryType;
    }

    public StatusMessage withThrowable(Throwable throwable) {
        if (throwable != null) {
            errorType = throwable.getClass().getName();
            errorMessage = throwable.getMessage();
        }
        return this;
    }

}
