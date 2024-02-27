// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.schema;

import de.telekom.eni.pandora.horizon.schema.SchemaStore;
import lombok.extern.slf4j.Slf4j;
import org.everit.json.schema.Schema;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@Slf4j
public class SchemaStoreConfiguration {

    @ConditionalOnMissingBean({SchemaStore.class})
    @AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
    @Bean
    public SchemaStore noOpSchemaStore() {
        return new SchemaStore() {
            @Override
            public Schema getSchemaForEventType(String environment, String eventType, String hub, String team) {
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
