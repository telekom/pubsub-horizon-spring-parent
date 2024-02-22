// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.schema.SchemaCacheService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.everit.json.schema.Schema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the SchemaCacheService
 * This AutoConfiguration is not enabled by default, as it might not be relevant for all applications.
 * If it´s enabled it only provides a default noop impl bean if not other implementation of SchemaCacheService is provided.
 *
 */
@Configuration
@Slf4j
public class SchemaCacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean({SchemaCacheService.class})
    public SchemaCacheService schemaCacheService() {
        return new SchemaCacheService() {
            @Override
            public Pair<Boolean, Schema> getSchemaForEventType(String environment, String eventType, String hub, String team) {
                log.debug("SchemaCacheService is not implemented, returning null");
                return null;
            }

            @Override
            public void pollSchemas() {
                log.debug("SchemaCacheService is not implemented");
            }
        };
    }

}
