// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.schema;

import org.apache.commons.lang3.tuple.Pair;
import org.everit.json.schema.Schema;

public interface SchemaCacheService {
    Pair<Boolean, Schema> getSchemaForEventType(String environment, String eventType, String hub, String team);

    void pollSchemas();
}
