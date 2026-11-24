package cz.cvut.pmassistant.llm;

import cz.cvut.pmassistant.llm.model.LlmRequest;
import cz.cvut.pmassistant.llm.model.LlmResponse;

/**
 * The port through which agents talk to a language model. Everything provider-specific (SDK classes, HTTP,
 * credentials, retries) lives behind it, so the agent loop and the domain never depend on a provider (NFR5).
 * <p>
 * An implementation turns a {@link LlmRequest} into the provider's request and the answer into a
 * {@link LlmResponse}, normalizing the stop reason. It reports failures as {@link LlmUnavailableException}.
 */
public interface LlmClient {

	/**
	 * Sends one request and returns the model's whole answer (no streaming).
	 *
	 * @throws LlmUnavailableException if the provider could not be reached or refused the request
	 */
	LlmResponse chat(LlmRequest request);

	/** The model this client uses, recorded with every run. */
	String model();
}
