// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.subscription.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.Date;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SnapshotId implements Comparable<SnapshotId> {
    public static final SnapshotId EMPTY_SNAPSHOT_ID = new SnapshotId(new ObjectId(Date.from(Instant.MIN)).toHexString());

    public static final String ZNODE_PREPARED = "/horizon/subscriptions/prepared";

    public static final String ZNODE_ACTIVATED = "/horizon/subscriptions/activated";

    private final ObjectId value;

    public SnapshotId(@JsonProperty("snapshotId") String snapshotId) {
        this.value = new ObjectId(snapshotId);
    }

    @Override
    public int compareTo(SnapshotId snapshotId) {
        return this.value.compareTo(snapshotId.value);
    }
}
