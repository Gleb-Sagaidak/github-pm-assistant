package cz.cvut.pmassistant.agent;

import java.time.Duration;

/**
 * The hard limits of one run (NFR8): it stops, as a failure, when any of them is reached.
 *
 * @param maxSteps        most model calls
 * @param maxDuration     most wall-clock time for the whole run
 * @param maxTotalTokens  most input plus output tokens over the whole run
 * @param toolTimeout     most time for one tool call
 * @param maxOutputTokens most tokens the model may write in one answer
 */
public record RunLimits(int maxSteps, Duration maxDuration, long maxTotalTokens, Duration toolTimeout,
		int maxOutputTokens) {

	public RunLimits {
		if (maxSteps <= 0 || maxTotalTokens <= 0 || maxOutputTokens <= 0) {
			throw new IllegalArgumentException("Run limits must be positive");
		}
		if (maxDuration.isNegative() || maxDuration.isZero() || toolTimeout.isNegative() || toolTimeout.isZero()) {
			throw new IllegalArgumentException("Run durations must be positive");
		}
	}

	/** Reasonable starting values; stages 3 and 7 tune them per agent. */
	public static RunLimits standard() {
		return new RunLimits(15, Duration.ofMinutes(5), 200_000, Duration.ofSeconds(30), 4096);
	}

	public RunLimits withMaxSteps(int steps) {
		return new RunLimits(steps, maxDuration, maxTotalTokens, toolTimeout, maxOutputTokens);
	}

	public RunLimits withMaxDuration(Duration duration) {
		return new RunLimits(maxSteps, duration, maxTotalTokens, toolTimeout, maxOutputTokens);
	}

	public RunLimits withMaxTotalTokens(long tokens) {
		return new RunLimits(maxSteps, maxDuration, tokens, toolTimeout, maxOutputTokens);
	}

	public RunLimits withToolTimeout(Duration timeout) {
		return new RunLimits(maxSteps, maxDuration, maxTotalTokens, timeout, maxOutputTokens);
	}
}
