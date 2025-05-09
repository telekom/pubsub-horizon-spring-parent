package de.telekom.eni.pandora.horizon.cache.fallback;

import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;

import java.util.List;
import java.util.Optional;

public interface JsonCacheFallback<T> {

    Optional<T> getByKey(String key) throws JsonCacheException;

    List<T> getQuery(Query query) throws JsonCacheException;

    List<T> getAll() throws JsonCacheException;

}
