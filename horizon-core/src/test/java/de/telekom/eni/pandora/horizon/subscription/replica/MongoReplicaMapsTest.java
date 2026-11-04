// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.subscription.replica;

import de.telekom.eni.pandora.horizon.subscription.snapshot.IndexedMap;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplicaMapsTest {

    private final ReplicaMaps maps = new ReplicaMaps();

    @Test
    void startsEmptyAndNotInitialized() {
        assertEquals(-1L, maps.getPrepared().get().getVersion());
        assertEquals(-1L, maps.getActive().get().getVersion());
        assertFalse(maps.isFullyInitialized());
    }

    @Test
    void prepareKeepsOnlyNewerVersions() {
        var v2 = map();

        maps.prepare(v2);
        maps.prepare(map());
        maps.prepare(map());

        assertSame(v2, maps.getPrepared().get());
        assertFalse(maps.isFullyInitialized());
    }

    @Test
    void activateKeepsOnlyNewerVersions() {
        var v2 = map();

        maps.activate(v2);
        maps.activate(map());
        maps.activate(map());

        assertSame(v2, maps.getActive().get());
        assertFalse(maps.isFullyInitialized());
    }

    @Test
    void activateWithoutArgumentPromotesPrepared() {
        var prepared = map();
        maps.prepare(prepared);

        maps.activate();

        assertSame(prepared, maps.getActive().get());
        assertTrue(maps.isFullyInitialized());
    }

    @Test
    void activateWithoutArgumentDoesNotDowngradeActive() {
        var active = map();
        maps.activate(active);
        maps.prepare(map());

        maps.activate();

        assertSame(active, maps.getActive().get());
    }

    private static IndexedMap map() {
        return new IndexedMap(List.of(), new ObjectId());
    }
}
