// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.extension;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@Slf4j
public class HazelcastExtension implements BeforeAllCallback, ExtensionContext.Store.CloseableResource {

    private static final String EXTENSION_NAME = "HazelcastExtension";

    public static HazelcastInstance hazelcastInstance;

    @Override
    public void beforeAll(ExtensionContext context) {
        if (hazelcastInstance == null) {
            hazelcastInstance = Hazelcast.newHazelcastInstance();
            log.info("Hazelcast test instance started");

            context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL).put(EXTENSION_NAME, this);
        }
    }

    @Override
    public void close() {
        if (hazelcastInstance != null) {
            log.info("Shutting down Hazelcast test instance...");
            hazelcastInstance.shutdown();
            hazelcastInstance = null;
        }
    }

    @TestConfiguration
    public static class HazelcastConfig {
        @Bean
        public HazelcastInstance hazelcastInstance() {
            return HazelcastExtension.hazelcastInstance;
        }
    }

}
