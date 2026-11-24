package cz.cvut.pmassistant.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed application configuration (prefix {@code app}).
 * Required values have no defaults: the application refuses to start if any is missing.
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

    /**
     * The language-model endpoint. It speaks the OpenAI chat-completions protocol (OpenAI itself, or a local
     * server such as Ollama). The API key is optional: local servers need none.
     */
    public record Llm(
            @Required String baseUrl,
            String apiKey,
            @Required String model) {
    }

    /** Defaults that a workspace can override later. */
    public record Defaults(@Positive long dailyTokenQuota) {
    }
}
