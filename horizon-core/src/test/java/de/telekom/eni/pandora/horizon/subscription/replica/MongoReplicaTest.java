// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.subscription.replica;

import de.telekom.eni.pandora.horizon.subscription.snapshot.IndexedMap;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplicaTest {

    @Test
    void readsFromActiveMapOnly() throws Exception {
        var activeDoc = ReplicaServiceTest.document("sub-a");
        var preparedDoc = ReplicaServiceTest.document("sub-b");
        var maps = new ReplicaMaps();
        maps.prepare(new IndexedMap(List.of(preparedDoc), new ObjectId()));
        maps.activate(new IndexedMap(List.of(activeDoc), new ObjectId()));

        var service = mock(ReplicaService.class);
        when(service.initMaps()).thenReturn(maps);

        var replica = new Replica(service);

        assertSame(activeDoc.getResource(), replica.get("sub-a"));
        assertNull(replica.get("sub-b"));
        assertEquals(List.of(activeDoc.getResource()), replica.get("playground", "event.v1"));
        assertNull(replica.get("prod", "event.v1"));
    }

    @Test
    void followsActivationAfterConstruction() throws Exception {
        var doc = ReplicaServiceTest.document("sub-a");
        var maps = new ReplicaMaps();
        var service = mock(ReplicaService.class);
        when(service.initMaps()).thenReturn(maps);

        var replica = new Replica(service);
        assertNull(replica.get("sub-a"));

        maps.activate(new IndexedMap(List.of(doc), new ObjectId()));

        assertSame(doc.getResource(), replica.get("sub-a"));
    }

    @Test
    void propagatesInitFailure() throws Exception {
        var service = mock(ReplicaService.class);
        when(service.initMaps()).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> new Replica(service));
    }
}
