package de.telekom.eni.pandora.horizon.model.meta;

import lombok.Getter;

/**
 * Represents the status of an event message within the Horizon workflow.
 */
public enum EventStatus {
	PROCESSED(0),
	WAITING(1),
	// Final
	DROPPED(2),
	FAILED(2),
	DELIVERED(2),
	UNKNOWN(2);

	@Getter
	private final int value;

	EventStatus(int value) {
		this.value = value;
	}

	public boolean canChangeTo(EventStatus newStatus) {
		return value <= newStatus.value;
	}
}
