package cz.cvut.pmassistant.github.http;

import java.time.Duration;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retries GitHub calls that failed for a temporary reason (NFR6).
 * <ul>
 * <li>Retried: {@link GitHubApiException.Transient} (5xx, network) and {@link GitHubApiException.RateLimited}
 * (429, 403 with rate-limit signs). Everything else (400, 401, 403, 404, 422, ...) fails at once.</li>
 * <li>If GitHub says how long to wait ({@code retry-after} or {@code x-ratelimit-reset}), we wait exactly that,
 * but not longer than {@code maxRateLimitWait}: a longer wait fails immediately instead of blocking a run.</li>
 * <li>Otherwise exponential backoff with jitter: {@code base}, {@code 2*base}, {@code 4*base}, ..., each
 * lengthened by a random 0-25 %.</li>
 * <li>{@code maxAttempts} counts the first call, so the default of 4 means three waits (1 s, 2 s, 4 s).</li>
 * </ul>
 */
public class RetryExecutor {

	private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);

	static final int DEFAULT_MAX_ATTEMPTS = 4;
	static final Duration DEFAULT_BASE_DELAY = Duration.ofSeconds(1);
	static final Duration DEFAULT_MAX_RATE_LIMIT_WAIT = Duration.ofSeconds(60);
	private static final double MAX_JITTER = 0.25;

	/** Waits between attempts; replaced in tests so they do not really sleep. */
	@FunctionalInterface
	public interface Sleeper {

		void sleep(Duration duration) throws InterruptedException;
	}

	private final int maxAttempts;
	private final Duration baseDelay;
	private final Duration maxRateLimitWait;
	private final Sleeper sleeper;
	private final DoubleSupplier random;

	/**
	 * @param random supplies values in [0, 1) for the jitter
	 */
	public RetryExecutor(int maxAttempts, Duration baseDelay, Duration maxRateLimitWait, Sleeper sleeper,
			DoubleSupplier random) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be at least 1");
		}
		this.maxAttempts = maxAttempts;
		this.baseDelay = baseDelay;
		this.maxRateLimitWait = maxRateLimitWait;
		this.sleeper = sleeper;
		this.random = random;
	}

	/** 4 attempts, 1/2/4 s backoff, rate-limit waits capped at 60 s. */
	public static RetryExecutor standard() {
		return new RetryExecutor(DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_DELAY, DEFAULT_MAX_RATE_LIMIT_WAIT,
				duration -> Thread.sleep(duration), Math::random);
	}

	/** A single attempt, never retries. */
	public static RetryExecutor noRetry() {
		return new RetryExecutor(1, Duration.ZERO, Duration.ZERO, duration -> {
		}, () -> 0);
	}

	public <T> T execute(Supplier<T> action) {
		for (int attempt = 1;; attempt++) {
			try {
				return action.get();
			}
			catch (GitHubApiException e) {
				Duration delay = delayBeforeRetry(e, attempt);
				if (delay == null) {
					throw e;
				}
				log.warn("GitHub {} {} failed ({}), attempt {}/{}, retrying in {} ms", e.method(), e.path(),
						e.status() == 0 ? "no response" : e.status(), attempt, maxAttempts, delay.toMillis());
				try {
					sleeper.sleep(delay);
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw e;
				}
			}
		}
	}

	/** How long to wait before the next attempt, or null if the failure must be reported now. */
	private Duration delayBeforeRetry(GitHubApiException e, int attempt) {
		if (attempt >= maxAttempts) {
			return null;
		}
		if (e instanceof GitHubApiException.RateLimited rateLimited) {
			Duration hint = rateLimited.retryAfter();
			if (hint == null) {
				return backoff(attempt);
			}
			return hint.compareTo(maxRateLimitWait) > 0 ? null : hint;
		}
		if (e instanceof GitHubApiException.Transient) {
			return backoff(attempt);
		}
		return null;
	}

	private Duration backoff(int attempt) {
		long base = baseDelay.toMillis() << (attempt - 1);
		return Duration.ofMillis(Math.round(base * (1 + MAX_JITTER * random.getAsDouble())));
	}
}
