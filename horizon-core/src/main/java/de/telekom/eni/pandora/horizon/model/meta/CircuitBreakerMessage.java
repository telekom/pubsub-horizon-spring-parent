package de.telekom.eni.pandora.horizon.model.meta;

import de.telekom.eni.pandora.horizon.model.common.Cacheable;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.time.Instant;
import java.util.Date;

@Getter
@Setter
public class CircuitBreakerMessage extends Cacheable {
	@Serial
	private static final long serialVersionUID = 600L;

	private String subscriptionId;
	private String subscriberId;

	private CircuitBreakerStatus status;

	private String environment;

	private String callbackUrl; // TODO: Think if we really need the callbackUrl

	private Date timestamp;

	/**
	 * Can be null
	 */
	private CircuitBreakerHealthCheck lastHealthCheck;
	private String assignedPodId;

	public CircuitBreakerMessage(String subscriptionId, CircuitBreakerStatus circuitBreakerStatus, String callbackUrl, String environment) {
		super(subscriptionId);
		this.subscriptionId = subscriptionId;
		this.subscriberId = ""; // TODO: Set in DUDE and put in constructor
		this.status = circuitBreakerStatus;
		this.environment = environment;
		this.callbackUrl = callbackUrl;

		this.timestamp = Date.from(Instant.now());
		this.lastHealthCheck = null;
		this.assignedPodId = "";
	}

	@Override
	protected String getType() {
		return "circuit-breaker";
	}

	@Override
	public int compareTo(Cacheable cachable) {
		if(cachable instanceof CircuitBreakerMessage circuitBreakerMessage) {
			// -1 -> move to left (beginning), 0 -> keep, 1 -> move to right (end)
			// sorted by timestamp millis, smallest first -> oldest timestamp first
			return getTimestamp().compareTo(circuitBreakerMessage.getTimestamp());
		}

		return super.compareTo(cachable);
	}
}
