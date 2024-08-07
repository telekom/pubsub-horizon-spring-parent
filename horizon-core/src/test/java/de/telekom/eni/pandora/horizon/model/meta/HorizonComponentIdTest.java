// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.model.meta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HorizonComponentIdTest {

    @Test
    void testEnumComponentIdsFromGroupIds() {
        HorizonComponentId multiplexer = HorizonComponentId.fromGroupId("multiplexer");
        assertEquals(HorizonComponentId.MULTIPLEXER, multiplexer);

        HorizonComponentId tasse = HorizonComponentId.fromGroupId("tasse");
        assertEquals(HorizonComponentId.TASSE, tasse);

        HorizonComponentId voyager = HorizonComponentId.fromGroupId("voyager");
        assertEquals(HorizonComponentId.VOYAGER, voyager);

        HorizonComponentId dude = HorizonComponentId.fromGroupId("dude");
        assertEquals(HorizonComponentId.DUDE, dude);

        HorizonComponentId producer = HorizonComponentId.fromGroupId("producer");
        assertEquals(HorizonComponentId.PRODUCER, producer);

        HorizonComponentId plunger = HorizonComponentId.fromGroupId("plunger");
        assertEquals(HorizonComponentId.PLUNGER, plunger);
    }

}