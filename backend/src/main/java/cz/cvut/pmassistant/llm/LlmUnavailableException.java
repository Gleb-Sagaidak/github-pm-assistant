package cz.cvut.pmassistant.llm;

import java.util.Objects;

/**
 * The language model could not be used: the provider was unreachable, slow, rate-limited, or rejected the
 * request. {@link #reason()} is a provider-independent code; the failed run is recorded with it (and, in
 * stage 4, the audit log).
 */
public class LlmUnavailableException extends RuntimeException {

	public enum Reason {
		RATE_LIMITED, TIMEOUT, NETWORK, SERVER_ERROR, UNAUTHORIZED, REJECTED, OTHER
	}

	private final Reason reason;

	public LlmUnavailableException(Reason reason, String message, Throwable cause) {
		super(message, cause);
		this.reason = Objects.requireNonNull(reason, "reason");
	}

	public LlmUnavailableException(Reason reason, String message) {
		this(reason, message, null);
	}

	public Reason reason() {
		return reason;
	}
}
