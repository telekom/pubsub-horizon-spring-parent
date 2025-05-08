// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.hazelcast.internal.util.StringUtil;
import com.hazelcast.map.IMap;
import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.model.event.PublishedEventMessage;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@AllArgsConstructor
public class DeDuplicationService {

    private final HazelcastInstance hazelcastInstance;

    private final CacheProperties cacheProperties;

    private IMap<String, String> getCache(@Nullable String cacheName) throws HazelcastInstanceNotActiveException {
        String actualCacheName = StringUtil.isNullOrEmptyAfterTrim(cacheName) ? cacheProperties.getDeDuplication().getDefaultCacheName() : cacheName;
        IMap<String, String> map = null;

        try {
            map = hazelcastInstance.getMap(actualCacheName);
        } catch (Exception e) {
            log.warn("Hazelcast instance is not active or cache not found: {}", e.getMessage());
        }
        return map;
    }

    public boolean isEnabled() {
        return cacheProperties.getDeDuplication().isEnabled();
    }

    private String generateKey(@NonNull String publishedEventMessageUUID, @NonNull String subscriptionId) {
        return publishedEventMessageUUID.concat("--").concat(subscriptionId);
    }

    public String generateKey(@NonNull PublishedEventMessage publishedEventMessage, String subscriptionId) {
        var publishedEventMessageUUID = publishedEventMessage.getUuid();
        return generateKey(publishedEventMessageUUID, subscriptionId);
    }

    public String generateKey(@NonNull SubscriptionEventMessage subscriptionEventMessage) {
        var additionalFields = Objects.requireNonNullElse(subscriptionEventMessage.getAdditionalFields(), new HashMap<>());
        var replicatedFromOrNull = (String) additionalFields.get("replicatedFrom");
        var publishedEventMessageUUID = Optional.ofNullable(replicatedFromOrNull).orElse(subscriptionEventMessage.getMultiplexedFrom());

        var subscriptionId = subscriptionEventMessage.getSubscriptionId();
        return generateKey(publishedEventMessageUUID, subscriptionId);
    }

    public boolean isDuplicate(String cacheName, @NonNull PublishedEventMessage publishedEventMessage, String subscriptionId) throws HazelcastInstanceNotActiveException {
        return isDuplicate(cacheName, generateKey(publishedEventMessage, subscriptionId));
    }

    public boolean isDuplicate(String cacheName, SubscriptionEventMessage subscriptionEventMessage) throws HazelcastInstanceNotActiveException {
        return isDuplicate(cacheName, generateKey(subscriptionEventMessage));
    }

    public boolean isDuplicate(@NonNull PublishedEventMessage publishedEventMessage, String subscriptionId) throws HazelcastInstanceNotActiveException {
        return isDuplicate("", generateKey(publishedEventMessage, subscriptionId));
    }

    public boolean isDuplicate(SubscriptionEventMessage subscriptionEventMessage) throws HazelcastInstanceNotActiveException {
        return isDuplicate("", generateKey(subscriptionEventMessage));
    }

    public boolean isDuplicate(String key) throws HazelcastInstanceNotActiveException {
        return isDuplicate("", key);
    }

    public boolean isDuplicate(String cacheName, String key) throws HazelcastInstanceNotActiveException {
        if (!isEnabled()) {
            return false;
        }

        return getCache(cacheName).containsKey(key);
    }

    public String get(String cacheName, @NonNull PublishedEventMessage publishedEventMessage, String subscriptionId) throws HazelcastInstanceNotActiveException {
        return get(cacheName, generateKey(publishedEventMessage, subscriptionId));
    }

    public String get(String cacheName, SubscriptionEventMessage subscriptionEventMessage) throws HazelcastInstanceNotActiveException {
        return get(cacheName, generateKey(subscriptionEventMessage));
    }

    public String get(@NonNull PublishedEventMessage publishedEventMessage, String subscriptionId) throws HazelcastInstanceNotActiveException {
        return get("", generateKey(publishedEventMessage, subscriptionId));
    }

    public String get(SubscriptionEventMessage subscriptionEventMessage) throws HazelcastInstanceNotActiveException {
        return get("", generateKey(subscriptionEventMessage));
    }

    public String get(String key) throws HazelcastInstanceNotActiveException {
        return get("", key);
    }

    public String get(String cacheName, String key) throws HazelcastInstanceNotActiveException {
        if (!isEnabled()) {
            return null;
        }

        return getCache(cacheName).get(key);
    }

    public String track(String cacheName, @NonNull PublishedEventMessage publishedEventMessage, String subscriptionId) throws HazelcastInstanceNotActiveException {
        return track(cacheName, generateKey(publishedEventMessage, subscriptionId), "");
    }

    public String track(String cacheName, SubscriptionEventMessage subscriptionEventMessage) throws HazelcastInstanceNotActiveException {
        return track(cacheName, generateKey(subscriptionEventMessage), subscriptionEventMessage.getUuid());
    }

    public String track(@NonNull PublishedEventMessage publishedEventMessage, String subscriptionId) throws HazelcastInstanceNotActiveException {
        return track("", publishedEventMessage, subscriptionId);
    }

    public String track(SubscriptionEventMessage subscriptionEventMessage) throws HazelcastInstanceNotActiveException {
        return track("", subscriptionEventMessage);
    }

    public String track(String key, @NonNull String value) throws HazelcastInstanceNotActiveException {
        return track("", key, value);
    }

    /**
     * Writes the key and value pair into the provided cache and returns the old value
     * @return null if disable or old value was null
     */
    public String track(String cacheName, String key, @NonNull String value) throws HazelcastInstanceNotActiveException {
        if (!isEnabled()) {
            return null;
        }

        return getCache(cacheName).put(key, value,
                cacheProperties.getDeDuplication().getTtlInSeconds(),
                TimeUnit.SECONDS,
                cacheProperties.getDeDuplication().getMaxIdleInSeconds(),
                TimeUnit.SECONDS);
    }

    public void clear(String key) throws HazelcastInstanceNotActiveException {
        clear("", key);
    }

    public void clear(@Nullable String cacheName, String key) throws HazelcastInstanceNotActiveException {
        if (!isEnabled()) {
            return;
        }

        getCache(cacheName).remove(key);
    }
}
