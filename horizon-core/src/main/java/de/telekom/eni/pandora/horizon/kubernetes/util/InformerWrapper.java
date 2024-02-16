// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.kubernetes.util;

import de.telekom.eni.pandora.horizon.kubernetes.InformerStoreInitSupport;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import lombok.Getter;
import lombok.Setter;

@Getter
public class InformerWrapper {


    private final SharedIndexInformer<? extends HasMetadata> informer;

    @Setter
    private InformerStoreInitSupport eventHandler;

    private final String namespace;

    public InformerWrapper(SharedIndexInformer<? extends HasMetadata> informer, String namespace) {
        this.informer = informer;
        this.namespace = namespace;
    }
}
