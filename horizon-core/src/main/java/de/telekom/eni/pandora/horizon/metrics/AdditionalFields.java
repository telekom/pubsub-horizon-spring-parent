package de.telekom.eni.pandora.horizon.metrics;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum AdditionalFields {

    START_TIME_TRUSTED("system-horizon-event-startTime"),
    SELECTION_FILTER_RESULT("selectionFilterResult"),
    SELECTION_FILTER_TRACE("selectionFilterTrace");

    public final String value;

}
