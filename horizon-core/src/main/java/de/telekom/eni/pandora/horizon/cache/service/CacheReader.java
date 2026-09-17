// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;

import java.util.List;
import java.util.Optional;

public interface CacheReader<T> {

    Optional<T> getByKey(String key) throws JsonCacheException;

    List<T> getQuery(Query query) throws JsonCacheException;

    List<T> getAll() throws JsonCacheException;

    boolean isReady();
}
