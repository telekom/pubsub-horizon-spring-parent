package de.telekom.eni.pandora.horizon.cache.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
public class DeDuplicationProperties {

    private boolean enabled = false;

    private String defaultCacheName = "deduplication";

    private long ttlInSeconds = 0;

    private long maxIdleInSeconds = 1800; // 30 minutes

}
