package cz.cvut.pmassistant.config;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * A required string configuration value: neither blank nor an unresolved placeholder.
 * <p>
 * When binding {@code @ConfigurationProperties}, Spring Boot does not fail on a {@code ${VAR}} without a default
 * when the variable is missing; it keeps the literal string {@code "${VAR}"}, which {@code @NotBlank} accepts.
 * Without this check the application would start with the "secret" {@code ${ANTHROPIC_API_KEY}}.
 */
@Documented
@Constraint(validatedBy = {})
@ReportAsSingleViolation
@NotBlank
@Pattern(regexp = "^(?!\\$\\{).*", message = "must be set (unresolved placeholder or empty value)")
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface Required {

	String message() default "must be set";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
