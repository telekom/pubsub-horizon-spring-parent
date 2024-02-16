package de.telekom.eni.pandora.horizon.metrics;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum MetricNames {
    Latency("latency"),
    Delivery("delivery"),
    EndToEndLatencyTardis("horizon.e2e.tardis.latency"),
    EndToEndLatencyCustomer("horizon.e2e.customer.latency");

    public final String value;

    public String getAsHeaderValue() {
        return this.value.replace(".","-");
    }
}
