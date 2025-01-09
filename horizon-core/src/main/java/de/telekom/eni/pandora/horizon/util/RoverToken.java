// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.HashMap;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoverToken {
	
	private String masterUrl;
	
	private String token;
	
	private String caCertificate;

	private HashMap<String, String> scope;
}
