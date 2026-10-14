// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.schema;

import de.telekom.eni.pandora.horizon.cache.service.JsonCacheService;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.PublisherResource;
import lombok.extern.slf4j.Slf4j;
import org.everit.json.schema.Schema;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONObject;

import java.util.Optional;

@Slf4j
public class CacheSchemaStore implements SchemaStore {

    private final JsonCacheService<PublisherResource> publisherCache;

    public CacheSchemaStore(JsonCacheService<PublisherResource> publisherCache) {
        this.publisherCache = publisherCache;
    }

    @Override
    public Schema getSchemaForEventType(String environment, String eventType, String hub, String team) {
        // is construction pattern: environment--eventType--hub--team
        // example: sit--order-check-v1--nto-hub--mufasa
        String key = String.format("%s--%s--%s--%s", environment, eventType, hub, team);
        try {
            Optional<PublisherResource> eventSpec = publisherCache.getByKey(key);

            return eventSpec
                    .map(PublisherResource::getSpec)
                    .flatMap(spec -> Optional.ofNullable(spec.getJsonSchema()))
                    .map(schemaString -> {
                        JSONObject schemaJson = new JSONObject(schemaString);
                        return SchemaLoader.load(schemaJson);
                    })
                    .orElse(null);

        } catch (JsonCacheException e) {
            log.error("PublisherResource not found in cache for key={}", key, e);
            return null;
        }
    }

    @Override
    public void pollSchemas() {
        log.debug("'CacheSchemaStore' does not need to poll schemas!");
    }

}
