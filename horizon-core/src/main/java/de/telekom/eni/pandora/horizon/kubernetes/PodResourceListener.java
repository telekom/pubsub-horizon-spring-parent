// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.kubernetes;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.OperationContext;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;

@Slf4j
public class PodResourceListener {

	private SharedInformerFactory informerFactory;

	final KubernetesClient kubernetesClient;

	final ResourceEventHandler<Pod> podHandler;

	final long resyncPeriodInMs;

	final String namespace;

	final String appName;


	public PodResourceListener(KubernetesClient kubernetesClient, ResourceEventHandler<Pod> podHandler, long resyncPeriodInMs, String namespace, String appName) {
		this.kubernetesClient = kubernetesClient;
		this.podHandler = podHandler;
		this.resyncPeriodInMs = resyncPeriodInMs;
		this.namespace = namespace;
		this.appName = appName;
	}

	public void init() {
		informerFactory = kubernetesClient.informers();
		informerFactory.addSharedInformerEventListener(ex -> log.error("Exception occurred while starting the informers: {}", ex.getMessage()));

		SharedIndexInformer<Pod> informer = createSharedIndexInformerFor(Pod.class, resyncPeriodInMs);
		informer.addEventHandler(podHandler);
	}

	public List<Pod> getAllPods() {
		var resources = kubernetesClient.resources(Pod.class);
		NonNamespaceOperation<Pod, KubernetesResourceList<Pod>, Resource<Pod>> nonNamespaceOperation = null;
		if (StringUtils.isNotBlank(namespace)) {
			nonNamespaceOperation = resources.inNamespace(namespace);
		}

		FilterWatchListDeletable<Pod, KubernetesResourceList<Pod>> filterWatchListDeletable = null;
		if (StringUtils.isNotBlank(appName)) {
			if(nonNamespaceOperation != null) {
				filterWatchListDeletable = nonNamespaceOperation.withLabels(Map.of("app", appName));
			} else {
				filterWatchListDeletable = resources.withLabels(Map.of("app", appName));
			}
		}

		if(filterWatchListDeletable != null) {
			return filterWatchListDeletable.list().getItems();
		} else if(nonNamespaceOperation != null) {
			return nonNamespaceOperation.list().getItems();
		} else {
			return resources.list().getItems();
		}
	}

	private <T extends HasMetadata> SharedIndexInformer<T> createSharedIndexInformerFor(Class<T> clazz, Long timeToWait) {
		var context = new OperationContext();
		if (StringUtils.isNotBlank(namespace)) {
			log.info("Restricting pod watching to namespace {}", namespace);
			context = context.withNamespace(namespace);
		}
		
		if (StringUtils.isNotBlank(appName)) {
			log.info("Restricting pod watching to labels app={}", appName);
			context = context.withLabels(Map.of("app", appName));
		}


		return informerFactory.sharedIndexInformerFor(clazz, context, timeToWait);
	}

	public void start() {
		log.info("Starting all registered pod informers");
		informerFactory.startAllRegisteredInformers();
	}

	public void stop() {
		log.info("Stopping all registered pod informers");
		informerFactory.stopAllRegisteredInformers(false);
	}
}
