package cz.cvut.pmassistant.llm.gateway;

import java.util.Optional;

import cz.cvut.pmassistant.llm.LlmUnavailableException;

/**
 * Tells the failures of the model service from bugs. Spring AI passes on whatever exception its provider module
 * throws (for OpenAI: the exceptions of the OpenAI SDK), so the translation into the provider-independent
 * {@link LlmUnavailableException} is done per provider.
 */
@FunctionalInterface
public interface ErrorClassifier {

	/**
	 * @return the failure as {@link LlmUnavailableException}, or empty if the exception is not a failure of the
	 *         model service (then it is a bug and must propagate unchanged)
	 */
	Optional<LlmUnavailableException> classify(RuntimeException failure);

	/** Recognises nothing: every exception propagates. */
	static ErrorClassifier none() {
		return failure -> Optional.empty();
	}
}
