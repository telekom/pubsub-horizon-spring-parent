package de.telekom.eni.pandora.horizon.cache.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Getter
@Setter
public class ZooKeeperProperties {

    // Can be disabled locally when the ZooKeeper-published addresses are not reachable.
    private boolean ensembleTrackerEnabled = true;

    private String connectString;

    private String preparedPath;

    private String activatedPath;

    private Duration connectionTimeout = Duration.ofSeconds(5);

    private Duration sessionTimeout = Duration.ofSeconds(30);
}
