// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.mongo.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/** Metadata identifying the complete subscription snapshot currently published as active in MongoDB. */
@Getter
@Setter
@NoArgsConstructor
@Document
public class SubscriptionSnapshotHead {

    @Id
    private String id;

    private String snapshotId;

    private Long documentCount;

    private Long revision;

    private String sourceHash;

    private Date createdAt;
}
