// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.model.meta;

import lombok.Getter;

public enum CircuitBreakerStatus {

	OPEN("OPEN"),
	CHECKING("CHECKING"),
	REPUBLISHING("REPUBLISHING");

	@Getter
	private final String value;

	CircuitBreakerStatus(String value) {
		this.value = value;
	}
}
