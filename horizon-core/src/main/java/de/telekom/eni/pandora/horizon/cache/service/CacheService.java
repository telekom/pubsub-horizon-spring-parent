// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.hazelcast.map.IMap;
import com.hazelcast.query.Predicate;
import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.model.common.Cacheable;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Optional;

@AllArgsConstructor
public class CacheService {

    private final HazelcastInstance hazelcastInstance;

    private final CacheProperties cacheProperties;

    private <T extends Cacheable> IMap<String, T> getCache() throws HazelcastInstanceNotActiveException {
        return hazelcastInstance.getMap(cacheProperties.getName());
    }

    public <T extends Cacheable> Optional<T> get(String primaryKey) throws HazelcastInstanceNotActiveException {
        IMap<String, T> cache = getCache();
        return Optional.ofNullable(cache.get(primaryKey));
    }

    public <T extends Cacheable> List<T> getValues() throws HazelcastInstanceNotActiveException {
        IMap<String, T> cache = getCache();
        var result = cache.values();
        return result.stream().toList();
    }

    public <T extends Cacheable> List<T> getWithQuery(Predicate<String, T> query) throws HazelcastInstanceNotActiveException {
        IMap<String, T> cache = getCache();
        var result = cache.values(query);
        return result.stream().toList();
    }

    public <T extends Cacheable> List<T> getWithQuery(Query query) throws HazelcastInstanceNotActiveException {
        return getWithQuery(query.toSqlPredicate());
    }

    public <T extends Cacheable> void update(T cacheable) throws HazelcastInstanceNotActiveException {
        getCache().put(cacheable.getKey(), cacheable);
    }

    public <T extends Cacheable> void update(String key, T cacheable) throws HazelcastInstanceNotActiveException {
        getCache().put(key, cacheable);
    }

    public void remove(String key) throws HazelcastInstanceNotActiveException { getCache().remove(key); }

    public <T extends Cacheable> void remove(T cachable) throws HazelcastInstanceNotActiveException { remove(cachable.getKey()); }

    public void clear() throws HazelcastInstanceNotActiveException { getCache().clear(); }
}
