// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.fallback;

import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;

import java.util.List;
import java.util.Optional;

public interface JsonCacheFallback<T> {

    Optional<T> getByKey(String key);

    List<T> getQuery(Query query);

    List<T> getAll();

}
