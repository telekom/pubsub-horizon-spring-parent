package de.telekom.eni.pandora.horizon.model.event.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Target({ElementType.TYPE})
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {EventDataValidator.class})
public @interface EventDataConstraint {

    String message() default "At least one of the fields data or dataref must be set";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}