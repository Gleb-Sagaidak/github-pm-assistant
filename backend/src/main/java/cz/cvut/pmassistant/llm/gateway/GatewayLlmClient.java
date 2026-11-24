package cz.cvut.pmassistant.llm.gateway;

import java.util.Objects;

import org.springframework.ai.chat.model.ChatModel;

import cz.cvut.pmassistant.llm.LlmClient;
import cz.cvut.pmassistant.llm.LlmUnavailableException;
import cz.cvut.pmassistant.llm.model.LlmRequest;
import cz.cvut.pmassistant.llm.model.LlmResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@link LlmClient} on top of Spring AI: Spring AI is only the <em>transport</em> to the model (connection, key,
 * timeouts, retries, metrics), whichever provider module is configured. The agent loop stays ours: Spring AI is
 * asked for one answer at a time, with the tools declared but never run by it, so limits, argument checks and
 * the recording of every step remain under our control.
 * <p>
 * Which provider is used is decided by the {@link ChatModel} that is passed in. What differs between providers
 * (the form of a failure, the name of the answer-length option) is given by the {@link ErrorClassifier} and the
 * {@link OutputLimit}.
 */
public class GatewayLlmClient implements LlmClient {

	private final ChatModel chatModel;
	private final String model;
	private final ErrorClassifier errors;
	private final OutputLimit outputLimit;
	private final RequestMapper requestMapper;
	private final ResponseMapper responseMapper;

	/**
	 * @param model the model name, recorded with every run
	 */
	public GatewayLlmClient(ChatModel chatModel, String model, ErrorClassifier errors, OutputLimit outputLimit) {
		this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
		this.model = Objects.requireNonNull(model, "model");
		this.errors = Objects.requireNonNull(errors, "errors");
		this.outputLimit = Objects.requireNonNull(outputLimit, "outputLimit");
		JsonMapper mapper = JsonMapper.builder().build();
		this.requestMapper = new RequestMapper(mapper);
		this.responseMapper = new ResponseMapper(mapper);
	}

	@Override
	public String model() {
		return model;
	}

	@Override
	public LlmResponse chat(LlmRequest request) {
		var prompt = requestMapper.toPrompt(request, chatModel, outputLimit);
		try {
			return responseMapper.toResponse(chatModel.call(prompt));
		}
		catch (LlmUnavailableException alreadyTranslated) {
			throw alreadyTranslated;
		}
		catch (RuntimeException failure) {
			throw errors.classify(failure).orElseThrow(() -> failure);
		}
	}
}
