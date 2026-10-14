// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class TestUtils {

    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    public static String readFileFromResources(String resourcePath) throws IOException {
        try (InputStream inputStream = TestUtils.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static <T> T readObjectFromResources(String resourcePath, Class<T> clazz) throws IOException {
        String content = readFileFromResources(resourcePath);
        ObjectMapper mapper = getMapperForFile(resourcePath);
        return mapper.readValue(content, clazz);
    }

    private static ObjectMapper getMapperForFile(String resourcePath) {
        String lowerCasePath = resourcePath.toLowerCase();
        if (lowerCasePath.endsWith(".yaml") || lowerCasePath.endsWith(".yml")) {
            return yamlMapper;
        }
        return jsonMapper;
    }

}
