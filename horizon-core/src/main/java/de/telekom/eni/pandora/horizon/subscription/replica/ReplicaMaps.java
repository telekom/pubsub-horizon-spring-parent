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
    // NB! make sure to guarantee the happens-before behavior: https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html#jls-17.5
    private final AtomicReference<IndexedMap> prepared;
    private final AtomicReference<IndexedMap> active;

    public ReplicaMaps() {
        this.prepared = new AtomicReference<>(IndexedMap.empty());
        this.active = new AtomicReference<>(IndexedMap.empty());
    }

    public void prepare(IndexedMap indexedMap) {
        prepared.accumulateAndGet(indexedMap, (current, given) ->  {
            if (given.getSnapshotId().compareTo(current.getSnapshotId()) > 0) {
                return given;
            } else  {
                return current;
            }
        });

    }

    public void activate(IndexedMap indexedMap) {
        active.accumulateAndGet(indexedMap, (current, given) -> {
            if (given.getSnapshotId().compareTo(current.getSnapshotId()) > 0) {
                return given;
            } else {
                return current;
            }
        });

        log.info("new index map with {} entries has been activated", indexedMap.getSize());
    }

    public void activate() {
        activate(prepared.get());
    }
}
