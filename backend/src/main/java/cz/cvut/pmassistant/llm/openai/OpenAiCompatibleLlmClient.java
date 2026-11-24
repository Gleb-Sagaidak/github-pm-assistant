package cz.cvut.pmassistant.llm.openai;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import cz.cvut.pmassistant.llm.LlmClient;
import cz.cvut.pmassistant.llm.LlmUnavailableException;
import cz.cvut.pmassistant.llm.LlmUnavailableException.Reason;
import cz.cvut.pmassistant.llm.model.LlmRequest;
import cz.cvut.pmassistant.llm.model.LlmResponse;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link LlmClient} for any server that speaks the OpenAI {@code chat/completions} protocol: OpenAI itself, or
 * a local or self-hosted one (Ollama, vLLM, LM Studio, ...). It is plain HTTP, so no provider SDK is needed.
 * <p>
 * Failures become {@link LlmUnavailableException} with a provider-independent reason. A rate limit, a server
 * error, a timeout or a network failure is retried (with backoff, honouring {@code Retry-After} up to
 * {@value #MAX_RETRY_AFTER_SECONDS} s); a rejected request or bad credentials are not. The request bodies, which
 * contain the text of issues, are never logged. The API key is sent only to the configured base URL: redirects
 * are not followed.
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

	static final long MAX_RETRY_AFTER_SECONDS = 30;
	private static final Duration BASE_BACKOFF = Duration.ofSeconds(1);
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final int MAX_MESSAGE_LENGTH = 300;
	private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

	/** Waits between attempts; replaced in tests so they do not really sleep. */
	@FunctionalInterface
	interface Sleeper {

		void sleep(Duration duration) throws InterruptedException;
	}

	/** A failure worth another attempt, with the server's own hint on how long to wait. */
	private static final class RetryableFailure extends LlmUnavailableException {

		private final Duration retryAfter;

		RetryableFailure(Reason reason, String message, Throwable cause, Duration retryAfter) {
			super(reason, message, cause);
			this.retryAfter = retryAfter;
		}
	}

	private final OpenAiCompatibleSettings settings;
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final OpenAiRequestMapper requestMapper = new OpenAiRequestMapper(mapper);
	private final OpenAiResponseMapper responseMapper = new OpenAiResponseMapper(mapper);
	private final RestClient client;
	private final Sleeper sleeper;

	public OpenAiCompatibleLlmClient(OpenAiCompatibleSettings settings) {
		this(settings, Thread::sleep);
	}

	OpenAiCompatibleLlmClient(OpenAiCompatibleSettings settings, Sleeper sleeper) {
		this.settings = settings;
		this.sleeper = sleeper;
		HttpClient httpClient = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(settings.timeout());
		this.client = RestClient.builder().requestFactory(requestFactory).build();
	}

	@Override
	public String model() {
		return settings.model();
	}

	@Override
	public LlmResponse chat(LlmRequest request) {
		String body = mapper.writeValueAsString(requestMapper.toBody(request, settings));
		for (int attempt = 0;; attempt++) {
			try {
				return attempt(body);
			}
			catch (RetryableFailure failure) {
				Duration wait = waitBeforeRetry(failure, attempt);
				if (wait == null) {
					throw failure;
				}
				log.warn("LLM request failed ({}), attempt {}/{}, retrying in {} ms", failure.reason(), attempt + 1,
						settings.maxRetries() + 1, wait.toMillis());
				try {
					sleeper.sleep(wait);
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw failure;
				}
			}
		}
	}

	/** How long to wait before the next attempt, or null if the failure must be reported now. */
	private Duration waitBeforeRetry(RetryableFailure failure, int attempt) {
		if (attempt >= settings.maxRetries()) {
			return null;
		}
		if (failure.retryAfter != null) {
			return failure.retryAfter.getSeconds() > MAX_RETRY_AFTER_SECONDS ? null : failure.retryAfter;
		}
		return BASE_BACKOFF.multipliedBy(1L << attempt);
	}

	private LlmResponse attempt(String body) {
		try {
			return client.post()
					.uri(settings.baseUrl() + "/chat/completions")
					.contentType(MediaType.APPLICATION_JSON)
					.accept(MediaType.APPLICATION_JSON)
					.headers(headers -> {
						if (settings.hasApiKey()) {
							headers.setBearerAuth(settings.apiKey());
						}
					})
					.body(body)
					.exchange((request, response) -> {
						int status = response.getStatusCode().value();
						String text = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
						if (response.getStatusCode().is2xxSuccessful()) {
							return responseMapper.toResponse(parse(text));
						}
						throw failureFor(status, text, response.getHeaders());
					}, true);
		}
		catch (ResourceAccessException e) {
			boolean timedOut = hasCause(e, HttpTimeoutException.class) || hasCause(e, SocketTimeoutException.class);
			throw new RetryableFailure(timedOut ? Reason.TIMEOUT : Reason.NETWORK,
					(timedOut ? "The model did not answer in " + settings.timeout().toSeconds() + " s"
							: "Cannot reach the model server: " + e.getMessage()),
					e, null);
		}
	}

	private JsonNode parse(String text) {
		try {
			return mapper.readTree(text);
		}
		catch (JacksonException e) {
			throw new LlmUnavailableException(Reason.OTHER, "The model server did not answer with JSON", e);
		}
	}

	private LlmUnavailableException failureFor(int status, String text, HttpHeaders headers) {
		String detail = "HTTP " + status + providerMessage(text);
		if (status == 401 || status == 403) {
			return new LlmUnavailableException(Reason.UNAUTHORIZED, detail);
		}
		if (status == 429) {
			return new RetryableFailure(Reason.RATE_LIMITED, detail, null, retryAfter(headers));
		}
		if (status == 408) {
			return new RetryableFailure(Reason.TIMEOUT, detail, null, null);
		}
		if (status >= 500) {
			return new RetryableFailure(Reason.SERVER_ERROR, detail, null, null);
		}
		return new LlmUnavailableException(Reason.REJECTED, detail);
	}

	/** {@code ": message"} from the error body in OpenAI's format, or a shortened raw body. */
	private String providerMessage(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		String message = text;
		try {
			JsonNode error = mapper.readTree(text).path("error");
			if (error.path("message").isString()) {
				message = error.path("message").asString();
			}
			else if (error.isString()) {
				message = error.asString();
			}
		}
		catch (JacksonException ignored) {
			// not JSON: use the raw text
		}
		return ": " + (message.length() <= MAX_MESSAGE_LENGTH ? message : message.substring(0, MAX_MESSAGE_LENGTH) + "...");
	}

	private static Duration retryAfter(HttpHeaders headers) {
		String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
		if (value == null) {
			return null;
		}
		try {
			return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private static boolean hasCause(Throwable throwable, Class<? extends IOException> type) {
		for (Throwable t = throwable; t != null; t = t.getCause()) {
			if (type.isInstance(t)) {
				return true;
			}
		}
		return false;
	}
}
