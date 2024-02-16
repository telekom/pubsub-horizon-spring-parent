package de.telekom.eni.pandora.horizon.tracing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties("pandora.tracing")
public class TracingProperties {
    private boolean debugEnabled = false;

}
