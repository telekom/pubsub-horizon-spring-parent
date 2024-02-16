// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.model.common;

import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;

@Getter
@Setter
public abstract class Cacheable implements Serializable, Comparable<Cacheable> {

	@Serial
	private static final long serialVersionUID = 1L;
	protected String key;

	protected String getType() {
		return this.getClass().getSimpleName();
	}

	protected Cacheable(String key) {
		this.key = key;
	}

	@Override
	public int compareTo(Cacheable cacheable) {
		var otherKey = cacheable.getKey();
		var thisKey = getKey();
		return otherKey.compareTo(thisKey);
	}
}
