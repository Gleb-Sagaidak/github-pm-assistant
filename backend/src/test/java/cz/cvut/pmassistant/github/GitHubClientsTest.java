package cz.cvut.pmassistant.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import cz.cvut.pmassistant.github.auth.AppJwtFactory;
import cz.cvut.pmassistant.github.http.RetryExecutor;
import cz.cvut.pmassistant.github.model.RepoRef;

/** Each client must act with the right credential: the App JWT, a narrowed installation token, or the user's. */
class GitHubClientsTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC);
	private static final RepoRef REPO = new RepoRef(4242, "cvut-team", "pm-assistant");
	private static final String JWT_PATTERN = "Bearer [A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+";

	@RegisterExtension
	static WireMockExtension github = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	private static KeyPair keys;

	@BeforeAll
	static void generateKey() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		keys = generator.generateKeyPair();
	}

	private GitHubClients clients() {
		AppJwtFactory jwt = new AppJwtFactory("Iv1.test", keys.getPrivate(), CLOCK);
		return new GitHubClients(github.baseUrl(), "2026-03-10", jwt, CLOCK, RetryExecutor.noRetry());
	}

	private static void stubInstallationToken(long installationId, String token) {
		github.stubFor(post(urlEqualTo("/app/installations/" + installationId + "/access_tokens"))
				.willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
						.withBody("{\"token\":\"" + token + "\",\"expires_at\":\"2026-11-21T13:00:00Z\"}")));
	}

	private static void stubLabels() {
		github.stubFor(get(urlPathEqualTo("/repos/cvut-team/pm-assistant/labels"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody(Fixtures.read("labels.json"))));
	}

	@Test
	void theReaderActsWithAReadOnlyInstallationToken() {
		stubInstallationToken(11, "ghs_read");
		stubLabels();

		assertThat(clients().readerFor(11).listLabels(REPO)).hasSize(3);

		github.verify(postRequestedFor(urlEqualTo("/app/installations/11/access_tokens"))
				.withHeader("Authorization", matching(JWT_PATTERN))
				.withRequestBody(equalToJson("{\"permissions\":{\"issues\":\"read\",\"metadata\":\"read\"}}")));
		github.verify(getRequestedFor(urlPathEqualTo("/repos/cvut-team/pm-assistant/labels"))
				.withHeader("Authorization", equalTo("Bearer ghs_read")));
	}

	@Test
	void theTokenIsReusedAndTheReaderIsCreatedOncePerInstallation() {
		stubInstallationToken(11, "ghs_read");
		stubLabels();
		GitHubClients clients = clients();

		clients.readerFor(11).listLabels(REPO);
		clients.readerFor(11).listLabels(REPO);

		assertThat(clients.readerFor(11)).isSameAs(clients.readerFor(11));
		github.verify(1, postRequestedFor(urlEqualTo("/app/installations/11/access_tokens")));
	}

	@Test
	void everyInstallationHasItsOwnTokenAndReader() {
		stubInstallationToken(11, "ghs_eleven");
		stubInstallationToken(22, "ghs_twenty_two");
		stubLabels();
		GitHubClients clients = clients();

		clients.readerFor(11).listLabels(REPO);
		clients.readerFor(22).listLabels(REPO);

		assertThat(clients.readerFor(11)).isNotSameAs(clients.readerFor(22));
		github.verify(getRequestedFor(urlPathEqualTo("/repos/cvut-team/pm-assistant/labels"))
				.withHeader("Authorization", equalTo("Bearer ghs_eleven")));
		github.verify(getRequestedFor(urlPathEqualTo("/repos/cvut-team/pm-assistant/labels"))
				.withHeader("Authorization", equalTo("Bearer ghs_twenty_two")));
	}

	@Test
	void theProjectsReaderUsesItsOwnNarrowedToken() {
		stubInstallationToken(11, "ghs_projects");
		github.stubFor(post(urlEqualTo("/graphql")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody(Fixtures.read("graphql-projects.json"))));

		assertThat(clients().projectsReaderFor(11).listProjects("cvut-team")).hasSize(2);

		github.verify(postRequestedFor(urlEqualTo("/app/installations/11/access_tokens"))
				.withRequestBody(equalToJson(
						"{\"permissions\":{\"organization_projects\":\"read\",\"metadata\":\"read\"}}")));
		github.verify(postRequestedFor(urlEqualTo("/graphql"))
				.withHeader("Authorization", equalTo("Bearer ghs_projects")));
	}

	@Test
	void theAppClientActsAsTheAppWithAJwt() {
		github.stubFor(get(urlPathEqualTo("/app/installations")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody(Fixtures.read("installations.json"))));

		assertThat(clients().appClient().listInstallations()).hasSize(2);

		github.verify(getRequestedFor(urlPathEqualTo("/app/installations"))
				.withHeader("Authorization", matching(JWT_PATTERN)));
	}

	@Test
	void theUserClientActsWithTheUsersToken() {
		github.stubFor(get(urlPathEqualTo("/user/installations")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody(Fixtures.read("user-installations.json"))));

		assertThat(clients().userClientFor(() -> "user-token").listInstallations()).hasSize(2);

		github.verify(getRequestedFor(urlPathEqualTo("/user/installations"))
				.withHeader("Authorization", equalTo("Bearer user-token")));
	}

	@Test
	void invalidatedTokensAreFetchedAgain() {
		stubInstallationToken(11, "ghs_read");
		stubLabels();
		GitHubClients clients = clients();
		clients.readerFor(11).listLabels(REPO);

		clients.tokens().invalidate(11);
		clients.readerFor(11).listLabels(REPO);

		github.verify(2, postRequestedFor(urlEqualTo("/app/installations/11/access_tokens")));
	}
}
