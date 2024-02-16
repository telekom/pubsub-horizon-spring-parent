package de.telekom.eni.pandora.horizon.kubernetes.resource;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.telekom.jsonfilter.operator.Operator;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubscriptionTrigger {

    private List<String> responseFilter;

    private Map<String, String> selectionFilter;

    private Operator advancedSelectionFilter;
}
