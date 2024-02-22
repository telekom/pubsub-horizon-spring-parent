// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.schema;

import org.everit.json.schema.Schema;

public interface SchemaStore {
    Schema getSchemaForEventType(String environment, String eventType, String hub, String team);

    void pollSchemas();
}
