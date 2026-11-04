// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.subscription.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import org.bson.types.ObjectId;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class Head {

    public static final String ZNODE_PREPARED = "/horizon/subscriptions/prepared";

    public static final String ZNODE_ACTIVATED = "/horizon/subscriptions/activated";

    private String snapshotId;

    public ObjectId getSnapshotObjectId() {
        return new ObjectId(getSnapshotId());
    }

}
