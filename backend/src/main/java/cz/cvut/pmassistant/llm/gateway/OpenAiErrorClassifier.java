package cz.cvut.pmassistant.llm.gateway;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;

import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;

import cz.cvut.pmassistant.llm.LlmUnavailableException;
import cz.cvut.pmassistant.llm.LlmUnavailableException.Reason;

/**
 * Translates the exceptions of the OpenAI SDK, which Spring AI's OpenAI module lets through, into
 * {@link LlmUnavailableException}. The SDK has already retried rate limits and server errors by itself (see
 * {@code spring.ai.openai.max-retries}), so what arrives here is what remained after the retries.
 */
public final class OpenAiErrorClassifier implements ErrorClassifier {

	@Override
	public Optional<LlmUnavailableException> classify(RuntimeException failure) {
		if (!(failure instanceof OpenAIException)) {
			return Optional.empty();
		}
		return Optional.of(new LlmUnavailableException(reasonOf(failure), describe(failure), failure));
	}

	private static Reason reasonOf(RuntimeException failure) {
		if (failure instanceof UnauthorizedException || failure instanceof PermissionDeniedException) {
			return Reason.UNAUTHORIZED;
		}
		if (failure instanceof RateLimitException) {
			return Reason.RATE_LIMITED;
		}
		if (failure instanceof OpenAIIoException) {
			return hasTimeoutCause(failure) ? Reason.TIMEOUT : Reason.NETWORK;
		}
		if (failure instanceof OpenAIServiceException service) {
			int status = service.statusCode();
			if (status == 408) {
				return Reason.TIMEOUT;
			}
			return status >= 500 ? Reason.SERVER_ERROR : Reason.REJECTED;
		}
		return Reason.OTHER;
	}

	private static String describe(RuntimeException failure) {
		if (failure instanceof OpenAIIoException) {
			return hasTimeoutCause(failure) ? "The model did not answer in time"
					: "Cannot reach the model server: " + rootMessage(failure);
		}
		return String.valueOf(failure.getMessage());
	}

	private static boolean hasTimeoutCause(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause()) {
			if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException
					|| (t instanceof InterruptedIOException && "timeout".equals(t.getMessage()))) {
				return true;
			}
		}
		return false;
	}

	private static String rootMessage(Throwable failure) {
		Throwable root = failure;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		return root.getClass().getSimpleName() + ": " + root.getMessage();
	}
}
