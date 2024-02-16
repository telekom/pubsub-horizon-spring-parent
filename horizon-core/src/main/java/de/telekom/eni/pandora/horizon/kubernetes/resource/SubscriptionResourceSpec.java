package de.telekom.eni.pandora.horizon.kubernetes.resource;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubscriptionResourceSpec {

    private Subscription subscription;

    private String sseActiveOnPod;

    private String environment;
}
