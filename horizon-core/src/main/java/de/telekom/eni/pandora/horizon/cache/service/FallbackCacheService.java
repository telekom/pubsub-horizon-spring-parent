// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

@Slf4j
public class FallbackCacheService<T> implements CacheReader<T> {

    private final CacheReader<T> primary;
    private final CacheReader<T> fallback;

    public FallbackCacheService(CacheReader<T> primary, CacheReader<T> fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public Optional<T> getByKey(String key) throws JsonCacheException {
        if (primary.isReady()) {
            try {
                return primary.getByKey(key);
            } catch (RuntimeException | JsonCacheException exception) {
                log.warn("Primary cache getByKey failed, using fallback", exception);
            }
        }
        return fallback.getByKey(key);
    }

    @Override
    public List<T> getQuery(Query query) throws JsonCacheException {
        if (primary.isReady()) {
            try {
                return primary.getQuery(query);
            } catch (RuntimeException | JsonCacheException exception) {
                log.warn("Primary cache getQuery failed, using fallback", exception);
            }
        }
        return fallback.getQuery(query);
    }

    @Override
    public List<T> getAll() throws JsonCacheException {
        if (primary.isReady()) {
            try {
                return primary.getAll();
            } catch (RuntimeException | JsonCacheException exception) {
                log.warn("Primary cache getAll failed, using fallback", exception);
            }
        }
        return fallback.getAll();
    }

    @Override
    public boolean isReady() {
        return primary.isReady() || fallback.isReady();
    }
}
