package cz.cvut.pmassistant.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import cz.cvut.pmassistant.github.http.GitHubApiException;
import cz.cvut.pmassistant.github.http.GitHubGraphQl;
import cz.cvut.pmassistant.github.http.GitHubHttp;
import cz.cvut.pmassistant.github.http.PaginationException;
import cz.cvut.pmassistant.github.http.RetryExecutor;
import cz.cvut.pmassistant.github.model.IssueState;
import cz.cvut.pmassistant.github.model.Iteration;
import cz.cvut.pmassistant.github.model.ProjectItem;
import cz.cvut.pmassistant.github.model.ProjectRef;
import cz.cvut.pmassistant.github.model.ProjectSummary;

class ProjectsGraphQlReaderTest {

	private static final ProjectRef BOARD = new ProjectRef("cvut-team", 1);

	@RegisterExtension
	static WireMockExtension github = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	private ProjectsGraphQlReader reader() {
		GitHubHttp http = new GitHubHttp(github.baseUrl(), "2026-03-10", () -> "tok",
				Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), RetryExecutor.noRetry());
		return new ProjectsGraphQlReader(new GitHubGraphQl(http));
	}

	private static ResponseDefinitionBuilder json(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
	}

	private static void stubQuery(String queryPart, String fixture) {
		github.stubFor(post(urlEqualTo("/graphql")).withRequestBody(containing(queryPart))
				.willReturn(json(Fixtures.read(fixture))));
	}

	@Test
	void listsOnlyOpenProjectsOfAnOrganization() {
		stubQuery("projectsV2", "graphql-projects.json");

		List<ProjectSummary> projects = reader().listProjects("cvut-team");

		assertThat(projects).containsExactly(
				new ProjectSummary("PVT_kwDOAAAAB84AAAAB", 1, "Sprint board"),
				new ProjectSummary("PVT_kwDOAAAAB84AAAAD", 3, "Roadmap"));
		github.verify(postRequestedFor(urlEqualTo("/graphql"))
				.withRequestBody(matchingJsonPath("$.variables.login", equalTo("cvut-team"))));
	}

	@Test
	void aPersonalAccountHasNoOrganizationBoards() {
		stubQuery("projectsV2", "graphql-error-not-found.json");

		assertThatThrownBy(() -> reader().listProjects("alice")).isInstanceOf(GitHubApiException.NotFound.class);
	}

	@Test
	void readsItemsAcrossPagesWithStatusAndIteration() {
		stubQuery("items(first", "graphql-items-page1.json");
		github.stubFor(post(urlEqualTo("/graphql")).withRequestBody(containing("items(first"))
				.withRequestBody(matchingJsonPath("$.variables.cursor", equalTo("CUR1")))
				.willReturn(json(Fixtures.read("graphql-items-page2.json"))));

		List<ProjectItem> items = reader().listItems(BOARD);

		assertThat(items).extracting(ProjectItem::number).containsExactly(7, 9, 3);

		ProjectItem inProgress = items.get(0);
		assertThat(inProgress.id()).isEqualTo("PVTI_1");
		assertThat(inProgress.title()).isEqualTo("Login fails with expired session");
		assertThat(inProgress.state()).isEqualTo(IssueState.OPEN);
		assertThat(inProgress.status()).isEqualTo("In progress");
		assertThat(inProgress.iterationId()).isEqualTo("it-2");
		assertThat(inProgress.repositoryFullName()).isEqualTo("cvut-team/pm-assistant");
		assertThat(inProgress.updatedAt()).isEqualTo(Instant.parse("2026-11-05T14:30:00Z"));

		ProjectItem done = items.get(1);
		assertThat(done.state()).isEqualTo(IssueState.CLOSED);
		assertThat(done.status()).isEqualTo("Done");
		assertThat(done.iterationId()).isNull();

		ProjectItem external = items.get(2);
		assertThat(external.repositoryFullName()).isEqualTo("cvut-team/infrastructure");
		assertThat(external.status()).isNull();
		assertThat(external.iterationId()).isNull();
	}

	@Test
	void skipsPullRequestsDraftsAndItemsWithoutContent() {
		stubQuery("items(first", "graphql-items-page1.json");
		github.stubFor(post(urlEqualTo("/graphql")).withRequestBody(containing("items(first"))
				.withRequestBody(matchingJsonPath("$.variables.cursor", equalTo("CUR1")))
				.willReturn(json(Fixtures.read("graphql-items-page2.json"))));

		List<ProjectItem> items = reader().listItems(BOARD);

		assertThat(items).extracting(ProjectItem::id).containsExactly("PVTI_1", "PVTI_4", "PVTI_5");
	}

	@Test
	void onlyTheFieldNamedStatusIsTheStatus() {
		stubQuery("items(first", "graphql-items-page1.json");
		github.stubFor(post(urlEqualTo("/graphql")).withRequestBody(containing("items(first"))
				.withRequestBody(matchingJsonPath("$.variables.cursor", equalTo("CUR1")))
				.willReturn(json(Fixtures.read("graphql-items-page2.json"))));

		// the first item also has Priority = High; that must not be taken as the status
		assertThat(reader().listItems(BOARD).get(0).status()).isEqualTo("In progress");
	}

	@Test
	void sendsOrganizationAndBoardNumber() {
		stubQuery("items(first", "graphql-items-page2.json");

		reader().listItems(new ProjectRef("cvut-team", 5));

		github.verify(postRequestedFor(urlEqualTo("/graphql"))
				.withRequestBody(matchingJsonPath("$.variables.login", equalTo("cvut-team")))
				.withRequestBody(matchingJsonPath("$.variables.number", equalTo("5"))));
	}

	@Test
	void aMissingBoardIsNotFound() {
		github.stubFor(post(urlEqualTo("/graphql")).willReturn(json("{\"data\":{\"organization\":{\"projectV2\":null}}}")));

		assertThatThrownBy(() -> reader().listItems(BOARD)).isInstanceOf(GitHubApiException.NotFound.class);
		assertThatThrownBy(() -> reader().listIterations(BOARD)).isInstanceOf(GitHubApiException.NotFound.class);
	}

	@Test
	void failsInsteadOfLoopingWhenTheCursorDoesNotAdvance() {
		github.stubFor(post(urlEqualTo("/graphql")).willReturn(json("""
				{"data":{"organization":{"projectV2":{"items":{
				  "pageInfo":{"hasNextPage":true,"endCursor":"STUCK"},"nodes":[]}}}}}""")));

		assertThatThrownBy(() -> reader().listItems(BOARD))
				.isInstanceOf(PaginationException.class)
				.hasMessageContaining("STUCK");
		github.verify(2, postRequestedFor(urlEqualTo("/graphql")));
	}

	@Test
	void readsIterationsOrderedByStartDateAcrossActiveAndCompleted() {
		stubQuery("fields(first", "graphql-iterations.json");

		List<Iteration> iterations = reader().listIterations(BOARD);

		assertThat(iterations).extracting(Iteration::title).containsExactly("Sprint 1", "Sprint 2", "Sprint 3");
		assertThat(iterations.get(1)).isEqualTo(new Iteration("it-2", "Sprint 2", LocalDate.of(2026, 11, 9), 14));
	}

	@Test
	void aBoardWithoutIterationFieldHasNoIterations() {
		stubQuery("fields(first", "graphql-iterations-none.json");

		assertThat(reader().listIterations(BOARD)).isEmpty();
	}

	@Test
	void findsTheCurrentIterationByDate() {
		stubQuery("fields(first", "graphql-iterations.json");
		ProjectsGraphQlReader reader = reader();

		assertThat(reader.currentIteration(BOARD, LocalDate.of(2026, 11, 15))).hasValueSatisfying(
				i -> assertThat(i.id()).isEqualTo("it-2"));
		assertThat(reader.currentIteration(BOARD, LocalDate.of(2026, 11, 22))).hasValueSatisfying(
				i -> assertThat(i.id()).as("last day of Sprint 2").isEqualTo("it-2"));
		assertThat(reader.currentIteration(BOARD, LocalDate.of(2026, 11, 23))).hasValueSatisfying(
				i -> assertThat(i.id()).as("first day of Sprint 3").isEqualTo("it-3"));
	}

	@Test
	void noCurrentIterationOutsideAllOfThem() {
		stubQuery("fields(first", "graphql-iterations.json");
		ProjectsGraphQlReader reader = reader();

		assertThat(reader.currentIteration(BOARD, LocalDate.of(2026, 10, 1))).isEmpty();
		assertThat(reader.currentIteration(BOARD, LocalDate.of(2027, 1, 1))).isEmpty();
	}
}
