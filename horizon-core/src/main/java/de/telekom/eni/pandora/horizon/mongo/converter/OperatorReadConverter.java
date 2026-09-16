// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.mongo.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import de.telekom.jsonfilter.operator.Operator;
import de.telekom.jsonfilter.serde.OperatorDeserializer;
import org.bson.Document;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

@ReadingConverter
public class OperatorReadConverter implements Converter<Document, Operator> {

    private final ObjectMapper objectMapper;

    public OperatorReadConverter() {
        var module = new SimpleModule();
        module.addDeserializer(Operator.class, new OperatorDeserializer());
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(module);
    }

    @Override
    public Operator convert(Document source) {
        try {
            return objectMapper.readValue(source.toJson(), Operator.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to deserialize MongoDB operator", exception);
        }
    }
}
