// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.utils;

import de.telekom.eni.pandora.horizon.model.common.Cacheable;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;

@Getter
@Setter
public class CacheServiceDummy extends Cacheable {

    @Serial
    private static final long serialVersionUID = 1000L;

    public String value;

    public CacheServiceDummy(String key, String value) {
        super(key);
        this.value = value;
    }

    @Override
    public String getType() {
        return "cache-service";
    }

}
