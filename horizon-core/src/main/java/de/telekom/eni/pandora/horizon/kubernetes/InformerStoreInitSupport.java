// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.kubernetes;

import io.fabric8.kubernetes.api.model.HasMetadata;

import java.util.List;

public interface InformerStoreInitSupport {
    <T extends HasMetadata> void addAll(List<T> list);
}
