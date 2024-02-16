// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.model.event.validation;

import de.telekom.eni.pandora.horizon.model.event.Event;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EventDataValidator implements ConstraintValidator<EventDataConstraint, Object> {

    private String message;

    @Override
    public void initialize(EventDataConstraint annotation) {
        this.message = annotation.message();
    }

    @Override
    public boolean isValid(Object objectToValidate, ConstraintValidatorContext context) {
        if (objectToValidate instanceof Event) {
            Event event = (Event) objectToValidate;

            if (event.getData() == null) {
                if (event.getDataRef() == null || event.getDataRef().trim().equals("")) {
                    log.error(message);
                    return false;
                }
            }
        }

        return true;
    }
}
