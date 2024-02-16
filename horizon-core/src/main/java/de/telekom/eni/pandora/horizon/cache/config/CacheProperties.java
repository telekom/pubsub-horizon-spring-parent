package de.telekom.eni.pandora.horizon.cache.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("horizon.cache")
public class CacheProperties {

    private String name = "cache";

    private String kubernetesServiceDns;

    private boolean enabled = false;

    private DeDuplicationProperties deDuplication = new DeDuplicationProperties();

    @Getter
    @Setter
    public static class DeDuplicationProperties {

        private boolean enabled = false;

        private String defaultCacheName = "deduplication";

        private long ttlInSeconds = 0;

        private long maxIdleInSeconds = 1800; // 30 minutes
    }

}
