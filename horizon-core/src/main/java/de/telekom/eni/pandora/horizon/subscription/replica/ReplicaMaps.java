// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.subscription.replica;

import de.telekom.eni.pandora.horizon.subscription.snapshot.IndexedMap;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Getter(AccessLevel.PACKAGE)
public class ReplicaMaps {

    private final AtomicReference<IndexedMap> prepared;

    private final AtomicReference<IndexedMap> active;

    public ReplicaMaps() {
        prepared = new AtomicReference<>(IndexedMap.empty());
        active = new AtomicReference<>(IndexedMap.empty());
    }

    public void prepare(IndexedMap indexedMap) {
        if (prepared.get().getVersion() >= indexedMap.getVersion()) {
            return;
        }

        prepared.set(indexedMap);
    }

    public void activate(IndexedMap indexedMap) {
        if (active.get().getVersion() >= indexedMap.getVersion()) {
            return;
        }

        active.set(indexedMap);
        log.info("new index map with {} entries has been activated", indexedMap.getSize());
    }

    public void activate() {
        activate(prepared.get());
    }

    public boolean isFullyInitialized() {
        return prepared.get().getVersion() > -1 &&
               active.get().getVersion() > -1;
    }

}
