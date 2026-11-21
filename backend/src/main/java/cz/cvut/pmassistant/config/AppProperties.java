package cz.cvut.pmassistant.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Типизированная конфигурация приложения (префикс {@code app}).
 * Обязательные значения без дефолтов: приложение не стартует, если они не заданы.
 */
@ConfigurationProperties(prefix = "app")
@Validated
public record AppProperties(
        @Valid @NotNull Github github,
        @Valid @NotNull Llm llm,
        @Valid @NotNull Defaults defaults) {

    public record Github(
            @Required String appId,
            @Required String appSlug,
            @Required String privateKeyPath,
            @Required String clientId,
            @Required String clientSecret,
            String webhookSecret,
            @Required String apiVersion) {
    }

    public record Llm(
            @Required String apiKey,
            @Required String model) {
    }

    /** Значения по умолчанию; workspace может их переопределить (этап 1.5). */
    public record Defaults(@Positive long dailyTokenQuota) {
    }
}
