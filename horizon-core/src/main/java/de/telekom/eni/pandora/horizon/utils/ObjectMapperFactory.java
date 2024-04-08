package de.telekom.eni.pandora.horizon.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import de.telekom.jsonfilter.operator.Operator;
import de.telekom.jsonfilter.serde.OperatorDeserializer;
import de.telekom.jsonfilter.serde.OperatorSerializer;

public class ObjectMapperFactory {

    public static ObjectMapper create() {
        return ObjectMapperFactory.withModules(new ObjectMapper(new YAMLFactory()));
    }

    private static ObjectMapper withModules(ObjectMapper objectMapper) {
        /*
            add module required for the JSON-Filter library
            in order to serialize/deserialize JsonPath Operator objects
         */
        {
            var module = new SimpleModule("JSON-Filter");
            module.addSerializer(Operator.class, new OperatorSerializer());
            module.addDeserializer(Operator.class, new OperatorDeserializer());

            objectMapper.registerModule(module);
        }

        return objectMapper;
    }
}
