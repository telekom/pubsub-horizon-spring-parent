package de.telekom.eni.pandora.horizon.kubernetes;

import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.util.InformerWrapper;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.OperationContext;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class SubscriptionResourceListener {

	private SharedInformerFactory informerFactory;

	final KubernetesClient kubernetesClient;

	final ResourceEventHandler<SubscriptionResource> eventHandler;

	private final InformerStoreInitHandler informerStoreInitHandler;

	final long resyncPeriodInMs;

	final String namespace;

	private final List<InformerWrapper> informers = new ArrayList<>();

	public SubscriptionResourceListener(KubernetesClient kubernetesClient, ResourceEventHandler<SubscriptionResource> eventHandler, InformerStoreInitHandler informerStoreInitHandler, long resyncPeriodInMs, String namespace) {
		this.kubernetesClient = kubernetesClient;
		this.eventHandler = eventHandler;
		this.informerStoreInitHandler = informerStoreInitHandler;
		this.resyncPeriodInMs = resyncPeriodInMs;
		this.namespace = namespace;
	}

	public void init() {
		informerFactory = kubernetesClient.informers();
		informerFactory.addSharedInformerEventListener(ex -> log.error("Exception occurred while starting the informers: {}", ex.getMessage()));

		SharedIndexInformer<SubscriptionResource> informer = createSharedIndexInformerFor(SubscriptionResource.class, resyncPeriodInMs);
		informer.addEventHandler(eventHandler);

		var wrapper = new InformerWrapper(informer, namespace);

		if (eventHandler instanceof InformerStoreInitSupport) {
			wrapper.setEventHandler((InformerStoreInitSupport) eventHandler);
		}

		informers.add(wrapper);
	}

	public List<SubscriptionResource> getAllSubscriptions() {
		var resources = kubernetesClient.resources(SubscriptionResource.class);

		if (StringUtils.isNotBlank(namespace)) {
			return resources.inNamespace(namespace).list().getItems();
		}

		return resources.list().getItems();
	}

	private <T extends CustomResource<?, ?>> SharedIndexInformer<T> createSharedIndexInformerFor(Class<T> clazz, Long timeToWait) {
		var context = new OperationContext();
		if (StringUtils.isNotBlank(namespace)) {
			log.info("Restricting watching to namespace {}", namespace);

			context = context.withNamespace(namespace);
		}

		return informerFactory.sharedIndexInformerForCustomResource(clazz, context, timeToWait);
	}

	public void start() {
		log.info("Starting initial cache syncing phase.");
		informerStoreInitHandler.handle(informers);
		log.info("Starting all informers.");
		informerFactory.startAllRegisteredInformers();

	}

	public void stop() {
		log.info("Stopping all registered informers");
		informerFactory.stopAllRegisteredInformers(false);
	}
}
