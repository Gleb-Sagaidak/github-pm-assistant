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
 * Обязательное строковое значение конфигурации: не пустое и не неразрешённый плейсхолдер.
 * <p>
 * Spring Boot при биндинге {@code @ConfigurationProperties} не падает на {@code ${VAR}} без дефолта,
 * если переменной нет, а оставляет строку {@code "${VAR}"} как есть, и {@code @NotBlank} её пропускает.
 * Без этой проверки приложение стартовало бы с «секретом» {@code ${ANTHROPIC_API_KEY}}.
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
