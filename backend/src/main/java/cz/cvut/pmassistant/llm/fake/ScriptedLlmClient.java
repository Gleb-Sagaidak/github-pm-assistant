package cz.cvut.pmassistant.llm.fake;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import cz.cvut.pmassistant.llm.LlmClient;
import cz.cvut.pmassistant.llm.model.LlmRequest;
import cz.cvut.pmassistant.llm.model.LlmResponse;

/**
 * A model that gives the answers it was told to give, in order, and writes down every request. Agent tests (and
 * later the evaluation scenarios) run on it, so they need no network and no real model (NFR12).
 * <p>
 * A script step is either a {@link LlmResponse} to return or a {@link RuntimeException} to throw (for example a
 * {@link cz.cvut.pmassistant.llm.LlmUnavailableException}). Asking for more answers than the script holds is an
 * error: a test that does so has a loop that runs longer than intended.
 */
public class ScriptedLlmClient implements LlmClient {

	private final Deque<Object> script = new ArrayDeque<>();
	private final List<LlmRequest> requests = new ArrayList<>();

	private ScriptedLlmClient(List<Object> steps) {
		for (Object step : steps) {
			if (!(step instanceof LlmResponse) && !(step instanceof RuntimeException)) {
				throw new IllegalArgumentException(
						"A script step must be an LlmResponse or a RuntimeException, got " + step);
			}
			script.add(step);
		}
	}

	public static ScriptedLlmClient of(Object... steps) {
		return new ScriptedLlmClient(List.of(steps));
	}

	@Override
	public synchronized LlmResponse chat(LlmRequest request) {
		requests.add(request);
		Object step = script.poll();
		if (step == null) {
			throw new IllegalStateException("ScriptedLlmClient: the script is exhausted, but request number "
					+ requests.size() + " arrived");
		}
		if (step instanceof RuntimeException failure) {
			throw failure;
		}
		return (LlmResponse) step;
	}

	@Override
	public String model() {
		return "scripted";
	}

	/** Every request received so far, in order. */
	public synchronized List<LlmRequest> requests() {
		return List.copyOf(requests);
	}

	public synchronized LlmRequest lastRequest() {
		if (requests.isEmpty()) {
			throw new IllegalStateException("No request was received yet");
		}
		return requests.get(requests.size() - 1);
	}

	public synchronized int callCount() {
		return requests.size();
	}

	/** Script steps not used yet. */
	public synchronized int remaining() {
		return script.size();
	}
}
