package de.telekom.eni.pandora.horizon.kubernetes;

import de.telekom.eni.pandora.horizon.exception.CouldNotSetInitialHorizonResourceStateException;
import de.telekom.eni.pandora.horizon.exception.CouldNotStartInformerException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public abstract class AbstractResourceListener<T extends HasMetadata> {

	protected final KubernetesClientWrapper kubernetesClientWrapper;
	private final ResourceEventHandler<T> handler;
	private final long resyncPeriodInMs;
	private final String namespace;
	private final Map<String, String> labels;
	private SharedIndexInformer<T> currentInformer;
	private final ApplicationEventPublisher applicationEventPublisher;

	private final AtomicBoolean isCurrentInformerStarting = new AtomicBoolean(false);

	public AbstractResourceListener(KubernetesClientWrapper kubernetesClientWrapper, ResourceEventHandler<T> handler, long resyncPeriodInMs, String namespace, Map<String, String> labels, ApplicationEventPublisher applicationEventPublisher) {
		this.kubernetesClientWrapper = kubernetesClientWrapper;
		this.handler = handler;
		this.resyncPeriodInMs = resyncPeriodInMs;
		this.namespace = namespace;
		this.labels = labels;
		this.applicationEventPublisher = applicationEventPublisher;
	}

	protected abstract Class<T> getApiTypeClass();

	private ListenerEvent.Type getListenerType() {
		var clazz = getApiTypeClass();

		if (clazz != null) {
			if (clazz.equals(Pod.class)) {
				return ListenerEvent.Type.POD_RESOURCE_LISTENER;
			} else if (clazz.equals(SubscriptionResource.class)) {
				return ListenerEvent.Type.SUBSCRIPTION_RESOURCE_LISTENER;
			}
		}

		return ListenerEvent.Type.UNKNOWN_RESOURCE_LISTENER;
	}

	private SharedIndexInformer<T> registerNewInformer() {
		var clazz = getApiTypeClass();

		assert clazz != null;

		var informer = kubernetesClientWrapper.registerNewInformer(clazz, namespace, labels, resyncPeriodInMs);
		var name = informer.getApiTypeClass().getSimpleName();

		informer.addEventHandler(handler);

		informer.exceptionHandler((isStarted, error) -> {
			log.error("Informer for {} resources ran into an error: {}.", name, error.getMessage());

			applicationEventPublisher.publishEvent(
					new ListenerEvent(
							getListenerType(),
							ListenerEvent.Event.INFORMER_EXCEPTION
					)
			);

			// default return statement:
			return isStarted && !(error instanceof WatcherException);
		});

		informer.stopped().whenComplete((s, error) -> {
			log.info("Stopped informer for {} resources.", name);

			if (error != null) {
				log.error("Informer for {} resources stopped unexpectedly with error {}.", name, error.getMessage());

				applicationEventPublisher.publishEvent(
						new ListenerEvent(
								getListenerType(),
								ListenerEvent.Event.INFORMER_STOPPED
						)
				);
			}
		});

		return informer;
	}

	private void setInitialState() throws CouldNotSetInitialHorizonResourceStateException {
		var resourceName = currentInformer.getApiTypeClass().getSimpleName();

		log.info("Setting the initial state of the informer store containing {} resources.", resourceName);
		try {
			var items = kubernetesClientWrapper.get(getApiTypeClass(), namespace);

			if (handler instanceof HorizonResourceEventHandler<T>) {
				((HorizonResourceEventHandler<T>) handler).onInitialStateSet(items);
			}

			this.currentInformer.initialState(items.stream());

			log.info("Successfully set initial state of the store containing {} resources.", resourceName);
		} catch (Exception e) {
			var errorMessage = String.format("Error occurred while setting the initial state for resource %s.", getListenerType());
			log.error(errorMessage, e);

			throw new CouldNotSetInitialHorizonResourceStateException(errorMessage, e);
		} finally {
			applicationEventPublisher.publishEvent(
					new ListenerEvent(
							getListenerType(),
							ListenerEvent.Event.INFORMER_INITIAL_STATE_SET
					)
			);
		}
	}

	public boolean start() throws CouldNotStartInformerException {
		try {
			if (currentInformer == null || !currentInformer.isRunning()) {
				currentInformer = registerNewInformer();
			}

			if (!currentInformer.isRunning() && !isCurrentInformerStarting.getAndSet(true)) {
				setInitialState();

				currentInformer.start();

				applicationEventPublisher.publishEvent(
						new ListenerEvent(
								getListenerType(),
								ListenerEvent.Event.INFORMER_STARTED
						)
				);

				log.info("Started informer for {} resources.", getApiTypeClass().getSimpleName());

				isCurrentInformerStarting.set(false);

				return true;
			}
		} catch (Exception e) {
			var errorMessage = String.format("Error occurred while starting the informer for %s.", getListenerType());
			log.error(errorMessage, e);

			throw new CouldNotStartInformerException(errorMessage, e);
		}

		return false;
	}

	public boolean stop() {
		if (currentInformer.isRunning()) {
			currentInformer.stop();

			return true;
		}

		return false;
	}

	public boolean isHealthy() {
		return currentInformer.isRunning() || isCurrentInformerStarting.get();
	}

	public List<T> getAll() {
		return kubernetesClientWrapper.get(getApiTypeClass(), namespace, labels);
	}
}