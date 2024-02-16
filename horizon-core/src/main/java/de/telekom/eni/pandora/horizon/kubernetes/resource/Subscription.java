package de.telekom.eni.pandora.horizon.kubernetes.resource;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Subscription implements Serializable {
    @Serial
    private static final long serialVersionUID = 300L;

    private String subscriptionId;

    private String subscriberId;

    private String publisherId;

    private List<String> additionalPublisherIds;

    private String createdAt;

    private SubscriptionTrigger trigger;

    private SubscriptionTrigger publisherTrigger;

    private List<String> appliedScopes;

    private String type;

    private String callback;

    private String payloadType;

    private String deliveryType;

    private boolean enforceGetHttpRequestMethodForHealthCheck;

    private boolean circuitBreakerOptOut;

    private String eventRetentionTime;

    public String getKey() {
        return subscriptionId;
    }
}
