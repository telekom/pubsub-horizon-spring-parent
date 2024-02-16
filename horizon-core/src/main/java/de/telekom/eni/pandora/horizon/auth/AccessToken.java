// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

public class AccessToken {
    private static final String ACCESS_TOKEN_FIELD = "access_token";
    private static final String EXPIRES_IN_FIELD = "expires_in";
    private static final int BUFFER_TIME_FOR_TOKEN_REFRESH = 5;

    private final String token;
    private final Instant issueTime;
    private final long timeToLive;

    private AccessToken(String token, Instant issueTime, long timeToLive) {
        this.token = token;
        this.issueTime = issueTime;
        this.timeToLive = timeToLive;
    }

    public static AccessToken of(final Map<String, Object> responseAsMap) {
        final Object token = responseAsMap.get(ACCESS_TOKEN_FIELD);
        Objects.requireNonNull(token, "Token is null");

        final Object expiresIn = responseAsMap.get(EXPIRES_IN_FIELD);
        Objects.requireNonNull(expiresIn, "Expires in is null");

        final var tokenAsString = token.toString();
        final var expiresInAsLong = Long.parseLong(expiresIn.toString());

        return new AccessToken(tokenAsString, Instant.now(), expiresInAsLong);
    }

    public boolean isExpired() {
        return issueTime.plus(timeToLive - BUFFER_TIME_FOR_TOKEN_REFRESH, ChronoUnit.SECONDS)
                .compareTo(Instant.now()) <= 0;
    }

    public String getToken() {
        return token;
    }

    @Override
    public int hashCode() {
        return Objects.hash(token, issueTime, timeToLive);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final AccessToken that = (AccessToken) o;
        return timeToLive == that.timeToLive &&
                Objects.equals(token, that.token) &&
                Objects.equals(issueTime, that.issueTime);
    }
}
