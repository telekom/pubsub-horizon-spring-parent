// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.subscription;

import de.telekom.eni.pandora.horizon.kubernetes.resource.Subscription;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResourceSpec;
import de.telekom.eni.pandora.horizon.mongo.model.SnapshotMongoDocument;
import de.telekom.eni.pandora.horizon.subscription.snapshot.IndexedMap;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndexedMapTest {

    @Test
    void indexesByIdAndByEnvironmentAndEventType() {
        var first = document("id-1", "playground", "event.v1");
        var second = document("id-2", "prod", "event.v1");
        var snapshotId = new ObjectId();

        var map = new IndexedMap(List.of(first, second), 42L, snapshotId);

        assertSame(first.getResource(), map.get("id-1"));
        assertSame(second.getResource(), map.get("id-2"));
        assertSame(first.getResource(), map.get("playground", "event.v1"));
        assertSame(second.getResource(), map.get("prod", "event.v1"));
        assertNull(map.get("unknown"));
        assertNull(map.get("playground", "other.v1"));
        assertEquals(42L, map.getVersion());
        assertEquals(snapshotId, map.getSnapshotId());
    }

    @Test
    void emptyMapHasNoEntries() {
        var map = IndexedMap.empty();

        assertNull(map.get("id-1"));
        assertNull(map.get("playground", "event.v1"));
        assertEquals(-1L, map.getVersion());
        assertNull(map.getSnapshotId());
    }

    @Test
    void createsKeyFromEnvironmentAndEventType() {
        assertEquals("playground#event.v1", IndexedMap.createEnvironmentAndEventTypeKey("playground", "event.v1"));
        assertEquals("playground#event.v1", IndexedMap.createEnvironmentAndEventTypeKey(document("id-1", "playground", "event.v1")));
    }

    private static SnapshotMongoDocument document(String snapshotId, String environment, String eventType) {
        var subscription = new Subscription();
        subscription.setType(eventType);

        var spec = new SubscriptionResourceSpec();
        spec.setEnvironment(environment);
        spec.setSubscription(subscription);

        var resource = new SubscriptionResource();
        resource.setSpec(spec);

        var document = new SnapshotMongoDocument();
        document.setSnapshotId(snapshotId);
        document.setResource(resource);
        return document;
    }
}
