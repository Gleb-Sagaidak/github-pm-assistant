package cz.cvut.pmassistant.llm.model;

/**
 * Token usage of one request or, summed, of a run. {@code cacheReadTokens} are input tokens served from the
 * provider's prompt cache (0 where there is none); they are part of {@code inputTokens}.
 */
public record Usage(int inputTokens, int outputTokens, int cacheReadTokens) {

	public static final Usage ZERO = new Usage(0, 0, 0);

	public Usage {
		if (inputTokens < 0 || outputTokens < 0 || cacheReadTokens < 0) {
			throw new IllegalArgumentException("Token counts cannot be negative");
		}
	}

	public Usage plus(Usage other) {
		return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
				cacheReadTokens + other.cacheReadTokens);
	}

	/** What counts against a run's token limit. */
	public long totalTokens() {
		return (long) inputTokens + outputTokens;
	}
}
