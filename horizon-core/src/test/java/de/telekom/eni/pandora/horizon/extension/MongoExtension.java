// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.extension;

import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

@Slf4j
public class MongoExtension implements BeforeAllCallback, ExtensionContext.Store.CloseableResource {

    private static final String EXTENSION_NAME = "MongoExtension";

    private static MongoServer mongoServer;

    @Override
    public void beforeAll(ExtensionContext context) {
        if (mongoServer == null) {
            mongoServer = new MongoServer(new MemoryBackend());
            mongoServer.bind();
            log.info("MongoDB test instance started");

            System.setProperty("horizon.mongo.url", mongoServer.getConnectionString());
            System.setProperty("horizon.mongo.enabled", "true");
            System.setProperty("horizon.mongo.rethrowExceptions", "true");

            context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL).put(EXTENSION_NAME, this);
        }
    }

    @Override
    public void close() {
        if (mongoServer != null) {
            log.info("Shutting down MongoDB test instance...");
            mongoServer.shutdown();
            mongoServer = null;
        }
    }
}
