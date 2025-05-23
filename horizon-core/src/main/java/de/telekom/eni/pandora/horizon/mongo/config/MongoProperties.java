// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.mongo.config;

import com.mongodb.WriteConcern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("horizon.mongo")
public class MongoProperties {

    private String clientId = "unset";

    private String url = "mongodb://root:ineedcoffee@localhost:27017";

    private Databases databases = new Databases();

    private WriteConcern writeConcern = WriteConcern.W1;

    private int maxConnections = 100;

    private int maxRetries = 10;

    private int retryDelay = 100;

    private long maxTimeout = 30000;

    private boolean enabled = false;

    private boolean rethrowExceptions = false;
}
