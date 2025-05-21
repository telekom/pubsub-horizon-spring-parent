// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.util;

import com.hazelcast.query.Predicate;
import com.hazelcast.query.Predicates;
import lombok.Builder;
import lombok.NonNull;

import java.util.*;

import static java.lang.String.format;

@Builder
public class Query {

    @NonNull
    private Class<?> type;

    @NonNull
    private final Map<String, List<Object>> matchers;

    public <T> Predicate<String, T> toSqlPredicate() {
        return Predicates.sql(toString());
    }

    @Override
    public String toString() {
        var query = new StringBuilder();

        List<String> matcherGroups = new ArrayList<>();
        matchers.forEach((key, values) -> {
            var matcher = new StringBuilder();
            if (values.size() > 1) matcher.append("(");

            List<String> matcherGroup = new ArrayList<>();
            values.forEach(obj -> matcherGroup.add(format("%s = %s", key, obj)));

            if (matcherGroup.size() > 1) {
                matcher.append(String.join(" OR ", matcherGroup));
            } else {
                matcher.append(matcherGroup.get(0));
            }
            if (values.size() > 1) matcher.append(")");

            matcherGroups.add(matcher.toString());
        });

        query.append(String.join(" AND ", matcherGroups));
        return query.toString();
    }

    public String getEventType() {
        if (matchers.containsKey("spec.subscription.type")) {
            List<Object> values = matchers.get("spec.subscription.type");
            if (!values.isEmpty()) {
                return values.get(0).toString();
            }
        }
        return null;
    }

    public static QueryBuilder builder(Class<?> type) {
        return new QueryBuilder().type(type).matchers(new HashMap<>());
    }

    public static class QueryBuilder {

        public QueryBuilder addMatcher(String key, Object value) {
            if (!matchers.containsKey(key)) matchers.put(key, new ArrayList<>());
            matchers.get(key).add(value);
            return this;
        }

        public QueryBuilder addMatchers(String key, Object ...values) {
            Arrays.stream(values).forEach( value -> addMatcher(key, value) );
            return this;
        }

    }

}
