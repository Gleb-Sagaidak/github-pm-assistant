package cz.cvut.pmassistant.github.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.core.ParameterizedTypeReference;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import cz.cvut.pmassistant.github.model.Label;

class GitHubHttpPaginationTest {

	@RegisterExtension
	static WireMockExtension github = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	private GitHubHttp http() {
		return http(3);
	}

	private GitHubHttp http(int maxPages) {
		return new GitHubHttp(github.baseUrl(), "2026-03-10", () -> "tok", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
				RetryExecutor.noRetry(), maxPages);
	}

	private static ResponseDefinitionBuilder page(String nextUrl, int... labelIds) {
		StringBuilder body = new StringBuilder("[");
		for (int i = 0; i < labelIds.length; i++) {
			body.append(i == 0 ? "" : ",")
					.append("{\"id\":").append(labelIds[i]).append(",\"name\":\"l").append(labelIds[i])
					.append("\",\"color\":\"ffffff\"}");
		}
		body.append("]");
		ResponseDefinitionBuilder response = aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody(body.toString());
		if (nextUrl != null) {
			response.withHeader("Link", "<" + nextUrl + ">; rel=\"next\", <" + nextUrl + ">; rel=\"last\"");
		}
		return response;
	}

	private static List<Long> ids(List<Label> labels) {
		return labels.stream().map(Label::id).toList();
	}

	@Test
	void collectsThreePagesInOrder() {
		github.stubFor(get(urlEqualTo("/labels?per_page=100"))
				.willReturn(page(github.baseUrl() + "/labels?per_page=100&page=2", 1, 2)));
		github.stubFor(get(urlEqualTo("/labels?per_page=100&page=2"))
				.willReturn(page(github.baseUrl() + "/labels?per_page=100&page=3", 3, 4)));
		github.stubFor(get(urlEqualTo("/labels?per_page=100&page=3")).willReturn(page(null, 5)));

		List<Label> labels = http().getAll("/labels", Label.class);

		assertThat(ids(labels)).containsExactly(1L, 2L, 3L, 4L, 5L);
		github.verify(3, getRequestedFor(urlPathEqualTo("/labels")));
	}

	@Test
	void keepsExistingQueryParametersAndAddsPerPage() {
		github.stubFor(get(urlEqualTo("/issues?state=open&per_page=100")).willReturn(page(null, 7)));

		assertThat(ids(http().getAll("/issues?state=open", Label.class))).containsExactly(7L);
	}

	@Test
	void doesNotDuplicatePerPage() {
		github.stubFor(get(urlEqualTo("/issues?per_page=30")).willReturn(page(null, 7)));

		assertThat(ids(http().getAll("/issues?per_page=30", Label.class))).containsExactly(7L);
	}

	@Test
	void returnsEmptyListForEmptyPage() {
		github.stubFor(get(urlEqualTo("/labels?per_page=100")).willReturn(page(null)));

		assertThat(http().getAll("/labels", Label.class)).isEmpty();
	}

	@Test
	void supportsPagesWrappedInAnObject() {
		record Wrapper(List<Label> labels) {
		}
		github.stubFor(get(urlEqualTo("/wrapped?per_page=100")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json")
				.withHeader("Link", "<" + github.baseUrl() + "/wrapped?per_page=100&page=2>; rel=\"next\"")
				.withBody("{\"total_count\":2,\"labels\":[{\"id\":1,\"name\":\"a\",\"color\":\"fff\"}]}")));
		github.stubFor(get(urlEqualTo("/wrapped?per_page=100&page=2")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json")
				.withBody("{\"total_count\":2,\"labels\":[{\"id\":2,\"name\":\"b\",\"color\":\"fff\"}]}")));

		List<Label> labels = http().getAll("/wrapped", ParameterizedTypeReference.forType(Wrapper.class),
				Wrapper::labels);

		assertThat(ids(labels)).containsExactly(1L, 2L);
	}

	@Test
	void failsInsteadOfReturningAPartialListWhenThereAreTooManyPages() {
		for (int p = 1; p <= 10; p++) {
			String url = p == 1 ? "/labels?per_page=100" : "/labels?per_page=100&page=" + p;
			github.stubFor(get(urlEqualTo(url))
					.willReturn(page(github.baseUrl() + "/labels?per_page=100&page=" + (p + 1), p)));
		}

		assertThatThrownBy(() -> http(3).getAll("/labels", Label.class))
				.isInstanceOf(PaginationException.class)
				.hasMessageContaining("More than 3 pages");

		github.verify(3, getRequestedFor(urlPathEqualTo("/labels")));
	}

	@Test
	void allowsExactlyMaxPages() {
		github.stubFor(get(urlEqualTo("/labels?per_page=100"))
				.willReturn(page(github.baseUrl() + "/labels?per_page=100&page=2", 1)));
		github.stubFor(get(urlEqualTo("/labels?per_page=100&page=2"))
				.willReturn(page(github.baseUrl() + "/labels?per_page=100&page=3", 2)));
		github.stubFor(get(urlEqualTo("/labels?per_page=100&page=3")).willReturn(page(null, 3)));

		assertThat(http(3).getAll("/labels", Label.class)).hasSize(3);
	}

	@Test
	void detectsALoopInNextLinks() {
		github.stubFor(get(urlEqualTo("/labels?per_page=100"))
				.willReturn(page(github.baseUrl() + "/labels?per_page=100", 1)));

		assertThatThrownBy(() -> http(50).getAll("/labels", Label.class))
				.isInstanceOf(PaginationException.class)
				.hasMessageContaining("loop");

		github.verify(1, getRequestedFor(urlPathEqualTo("/labels")));
	}

	@Test
	void doesNotFollowANextLinkToAnotherHost() {
		github.stubFor(get(urlEqualTo("/labels?per_page=100"))
				.willReturn(page("https://evil.example/labels?page=2", 1)));

		assertThatThrownBy(() -> http().getAll("/labels", Label.class))
				.isInstanceOf(PaginationException.class)
				.hasMessageContaining("another host");

		github.verify(1, getRequestedFor(urlPathEqualTo("/labels")));
	}

	@Test
	void doesNotFollowANextLinkToAnotherPortOfTheSameHost() {
		github.stubFor(get(urlEqualTo("/labels?per_page=100"))
				.willReturn(page("http://localhost:1/labels?page=2", 1)));

		assertThatThrownBy(() -> http().getAll("/labels", Label.class)).isInstanceOf(PaginationException.class);
	}

	@Test
	void propagatesAnErrorOnALaterPageInsteadOfReturningWhatWasRead() {
		github.stubFor(get(urlEqualTo("/labels?per_page=100"))
				.willReturn(page(github.baseUrl() + "/labels?per_page=100&page=2", 1)));
		github.stubFor(get(urlEqualTo("/labels?per_page=100&page=2")).willReturn(aResponse().withStatus(404)));

		assertThatThrownBy(() -> http().getAll("/labels", Label.class))
				.isInstanceOf(GitHubApiException.NotFound.class);
	}
}
