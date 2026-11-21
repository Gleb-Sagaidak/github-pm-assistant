package cz.cvut.pmassistant.github.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import cz.cvut.pmassistant.github.http.GitHubApiException.NotFound;
import cz.cvut.pmassistant.github.http.GitHubApiException.RateLimited;
import cz.cvut.pmassistant.github.http.GitHubApiException.Transient;

/** The audit log (FR10) relies on method, path, status and request id being kept on every error. */
class GitHubApiExceptionTest {

	@Test
	void keepsRequestDetailsAndMentionsThemInMessage() {
		NotFound e = new NotFound("Not Found", "GET", "/repos/o/r/issues/99", "ABCD:1234");

		assertThat(e.method()).isEqualTo("GET");
		assertThat(e.path()).isEqualTo("/repos/o/r/issues/99");
		assertThat(e.status()).isEqualTo(404);
		assertThat(e.requestId()).isEqualTo("ABCD:1234");
		assertThat(e.getMessage()).contains("GET /repos/o/r/issues/99", "404", "ABCD:1234", "Not Found");
	}

	@Test
	void rateLimitedCarriesServerHint() {
		RateLimited e = new RateLimited("slow down", "GET", "/x", 429, "R1", Duration.ofSeconds(3));

		assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(3));
		assertThat(e.status()).isEqualTo(429);
	}

	@Test
	void networkFailureHasNoStatusAndKeepsCause() {
		RuntimeException cause = new RuntimeException("connection reset");
		Transient e = new Transient("I/O error", "POST", "/graphql", 0, null, cause);

		assertThat(e.status()).isZero();
		assertThat(e.requestId()).isNull();
		assertThat(e.getCause()).isSameAs(cause);
		assertThat(e.getMessage()).contains("no response");
	}

	@Test
	void everySubtypeIsAGitHubApiException() {
		assertThat(new GitHubApiException.Unauthorized("bad creds", "GET", "/x", null)).isInstanceOf(GitHubApiException.class);
		assertThat(new GitHubApiException.Forbidden("no permission", "GET", "/x", null)).isInstanceOf(GitHubApiException.class);
		assertThat(new GitHubApiException("Validation Failed", "POST", "/x", 422, "R2", null).status()).isEqualTo(422);
	}
}
