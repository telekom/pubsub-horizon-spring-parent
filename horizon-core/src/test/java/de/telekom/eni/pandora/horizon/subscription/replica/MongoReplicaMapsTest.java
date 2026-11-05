// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.subscription.replica;

import de.telekom.eni.pandora.horizon.subscription.snapshot.IndexedMap;
import de.telekom.eni.pandora.horizon.subscription.snapshot.SnapshotId;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MongoReplicaMapsTest {

    private final ReplicaMaps maps = new ReplicaMaps();

    @Test
    void startsEmptyAndNotInitialized() {
        assertEquals(SnapshotId.EMPTY_SNAPSHOT_ID, maps.getPrepared().get().getSnapshotId());
        assertEquals(SnapshotId.EMPTY_SNAPSHOT_ID, maps.getActive().get().getSnapshotId());
    }
}
