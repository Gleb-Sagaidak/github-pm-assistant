package cz.cvut.pmassistant.github.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RetryExecutorTest {

	private final List<Duration> sleeps = new ArrayList<>();

	private RetryExecutor executor(double jitter) {
		return new RetryExecutor(4, Duration.ofSeconds(1), Duration.ofSeconds(60), sleeps::add, () -> jitter);
	}

	private static GitHubApiException.Transient serverError() {
		return new GitHubApiException.Transient("boom", "GET", "/x", 502, "R", null);
	}

	private static GitHubApiException.RateLimited rateLimited(Duration hint) {
		return new GitHubApiException.RateLimited("slow", "GET", "/x", 429, "R", hint);
	}

	@AfterEach
	void clearInterruptFlag() {
		Thread.interrupted();
	}

	@Test
	void returnsImmediatelyOnSuccess() {
		assertThat(executor(0).execute(() -> "ok")).isEqualTo("ok");
		assertThat(sleeps).isEmpty();
	}

	@Test
	void backsOffExponentiallyAndGivesUpAfterFourAttempts() {
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> executor(0).execute(() -> {
			calls.incrementAndGet();
			throw serverError();
		})).isInstanceOf(GitHubApiException.Transient.class);

		assertThat(calls).hasValue(4);
		assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
	}

	@Test
	void jitterLengthensTheDelayByUpToAQuarter() {
		assertThatThrownBy(() -> executor(0.999).execute(() -> {
			throw serverError();
		})).isInstanceOf(GitHubApiException.class);

		assertThat(sleeps).hasSize(3);
		assertThat(sleeps.get(0)).isBetween(Duration.ofMillis(1240), Duration.ofMillis(1250));
		assertThat(sleeps.get(2)).isBetween(Duration.ofMillis(4960), Duration.ofMillis(5000));
	}

	@Test
	void succeedsOnALaterAttempt() {
		AtomicInteger calls = new AtomicInteger();

		String result = executor(0).execute(() -> {
			if (calls.incrementAndGet() < 3) {
				throw serverError();
			}
			return "ok";
		});

		assertThat(result).isEqualTo("ok");
		assertThat(sleeps).hasSize(2);
	}

	@Test
	void waitsExactlyAsLongAsGitHubAsks() {
		AtomicInteger calls = new AtomicInteger();

		String result = executor(0.5).execute(() -> {
			if (calls.incrementAndGet() == 1) {
				throw rateLimited(Duration.ofSeconds(7));
			}
			return "ok";
		});

		assertThat(result).isEqualTo("ok");
		assertThat(sleeps).containsExactly(Duration.ofSeconds(7));
	}

	@Test
	void usesBackoffWhenRateLimitedWithoutHint() {
		AtomicInteger calls = new AtomicInteger();

		executor(0).execute(() -> {
			if (calls.incrementAndGet() == 1) {
				throw rateLimited(null);
			}
			return "ok";
		});

		assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
	}

	@Test
	void failsAtOnceWhenTheRequestedWaitExceedsTheCap() {
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> executor(0).execute(() -> {
			calls.incrementAndGet();
			throw rateLimited(Duration.ofSeconds(61));
		})).isInstanceOf(GitHubApiException.RateLimited.class);

		assertThat(calls).hasValue(1);
		assertThat(sleeps).isEmpty();
	}

	@Test
	void waitsForTheCapItself() {
		AtomicInteger calls = new AtomicInteger();

		executor(0).execute(() -> {
			if (calls.incrementAndGet() == 1) {
				throw rateLimited(Duration.ofSeconds(60));
			}
			return "ok";
		});

		assertThat(sleeps).containsExactly(Duration.ofSeconds(60));
	}

	@Test
	void doesNotRetryClientErrors() {
		List<GitHubApiException> permanent = List.of(
				new GitHubApiException.NotFound("nf", "GET", "/x", "R"),
				new GitHubApiException.Unauthorized("no", "GET", "/x", "R"),
				new GitHubApiException.Forbidden("no", "GET", "/x", "R"),
				new GitHubApiException("invalid", "POST", "/x", 422, "R", null),
				new GitHubApiException("bad", "POST", "/x", 400, "R", null));

		for (GitHubApiException failure : permanent) {
			AtomicInteger calls = new AtomicInteger();

			assertThatThrownBy(() -> executor(0).execute(() -> {
				calls.incrementAndGet();
				throw failure;
			})).isSameAs(failure);

			assertThat(calls).as(failure.getClass().getSimpleName() + " " + failure.status()).hasValue(1);
		}
		assertThat(sleeps).isEmpty();
	}

	@Test
	void doesNotSwallowNonGitHubExceptions() {
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> executor(0).execute(() -> {
			calls.incrementAndGet();
			throw new IllegalStateException("bug");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(calls).hasValue(1);
	}

	@Test
	void stopsAndKeepsTheInterruptFlagWhenInterruptedWhileWaiting() {
		RetryExecutor interrupted = new RetryExecutor(4, Duration.ofSeconds(1), Duration.ofSeconds(60),
				duration -> {
					throw new InterruptedException();
				}, () -> 0);
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> interrupted.execute(() -> {
			calls.incrementAndGet();
			throw serverError();
		})).isInstanceOf(GitHubApiException.Transient.class);

		assertThat(calls).hasValue(1);
		assertThat(Thread.currentThread().isInterrupted()).isTrue();
	}

	@Test
	void noRetryMakesExactlyOneAttempt() {
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> RetryExecutor.noRetry().execute(() -> {
			calls.incrementAndGet();
			throw serverError();
		})).isInstanceOf(GitHubApiException.Transient.class);

		assertThat(calls).hasValue(1);
	}

	@Test
	void rejectsZeroAttempts() {
		assertThatThrownBy(() -> new RetryExecutor(0, Duration.ZERO, Duration.ZERO, d -> {
		}, () -> 0)).isInstanceOf(IllegalArgumentException.class);
	}
}
