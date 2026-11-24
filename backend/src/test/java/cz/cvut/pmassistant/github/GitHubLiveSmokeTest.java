package cz.cvut.pmassistant.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import cz.cvut.pmassistant.LiveConfig;
import cz.cvut.pmassistant.github.auth.AppJwtFactory;
import cz.cvut.pmassistant.github.http.RetryExecutor;
import cz.cvut.pmassistant.github.model.Comment;
import cz.cvut.pmassistant.github.model.Installation;
import cz.cvut.pmassistant.github.model.Issue;
import cz.cvut.pmassistant.github.model.IssueLink;
import cz.cvut.pmassistant.github.model.IssueStateFilter;
import cz.cvut.pmassistant.github.model.Iteration;
import cz.cvut.pmassistant.github.model.Label;
import cz.cvut.pmassistant.github.model.ProjectItem;
import cz.cvut.pmassistant.github.model.ProjectRef;
import cz.cvut.pmassistant.github.model.ProjectSummary;
import cz.cvut.pmassistant.github.model.RepoRef;
import cz.cvut.pmassistant.github.model.TimelineEvent;
import cz.cvut.pmassistant.github.model.User;

/**
 * Manual check against real GitHub: reads everything the agents need from a test repository and prints it.
 * It is NOT part of the normal build (tag {@code live}); run it with
 *
 * <pre>
 * ./mvnw test -Plive -Dtest=GitHubLiveSmokeTest
 * </pre>
 *
 * Settings come from environment variables or the repository's {@code .env} file:
 * <ul>
 * <li>{@code GITHUB_APP_CLIENT_ID} and {@code GITHUB_APP_PRIVATE_KEY_PATH}: the GitHub App (already in .env);</li>
 * <li>{@code LIVE_GITHUB_INSTALLATION_ID}: the installation of the App on the test account;</li>
 * <li>{@code LIVE_GITHUB_REPO}: {@code owner/name} of the test repository;</li>
 * <li>{@code LIVE_GITHUB_PROJECT}: optional number of an organization's Projects v2 board.</li>
 * </ul>
 * Without {@code LIVE_GITHUB_INSTALLATION_ID} and {@code LIVE_GITHUB_REPO} the test is skipped. It only reads.
 */
@Tag("live")
class GitHubLiveSmokeTest {

	private static final String API_URL = "https://api.github.com";
	private static final String API_VERSION = "2026-03-10";

	private static LiveConfig config;
	private static GitHubClients clients;
	private static long installationId;
	private static RepoRef repo;

	@BeforeAll
	static void connect() {
		config = new LiveConfig();
		assumeTrue(config.get("LIVE_GITHUB_INSTALLATION_ID") != null && config.get("LIVE_GITHUB_REPO") != null,
				"LIVE_GITHUB_INSTALLATION_ID and LIVE_GITHUB_REPO are not set: live smoke test skipped");
		installationId = Long.parseLong(config.require("LIVE_GITHUB_INSTALLATION_ID"));
		String[] fullName = config.require("LIVE_GITHUB_REPO").split("/", 2);
		// The numeric repository id is not needed to read; it is only part of the reference.
		repo = new RepoRef(0, fullName[0], fullName[1]);
		Clock clock = Clock.systemUTC();
		AppJwtFactory jwt = AppJwtFactory.fromPemFile(config.require("GITHUB_APP_CLIENT_ID"),
				config.path("GITHUB_APP_PRIVATE_KEY_PATH"), clock);
		clients = new GitHubClients(API_URL, API_VERSION, jwt, clock, RetryExecutor.standard());
	}

	@Test
	void theAppSeesItsInstallationOnTheTestRepository() {
		GitHubAppClient app = clients.appClient();

		List<Installation> installations = app.listInstallations();
		Optional<Installation> forRepo = app.findInstallationForRepo(repo.owner(), repo.name());

		print("installations of the app", installations.size());
		assertThat(installations).extracting(Installation::id).contains(installationId);
		assertThat(forRepo).hasValueSatisfying(installation -> {
			print("installation of " + repo.fullName(), installation.id() + " on " + installation.account().login()
					+ " (" + installation.account().type() + "), permissions " + installation.permissions());
			assertThat(installation.id()).isEqualTo(installationId);
		});
	}

	@Test
	void readsLabelsCollaboratorsAndIssues() {
		GitHubReader reader = clients.readerFor(installationId);

		List<Label> labels = reader.listLabels(repo);
		List<User> collaborators = reader.listCollaborators(repo);
		List<Issue> issues = reader.listIssues(repo, IssueStateFilter.ALL, null);

		print("labels", labels.stream().map(l -> l.name() + "#" + l.id()).toList());
		print("collaborators", collaborators.stream().map(User::login).toList());
		print("issues (all, without pull requests)", issues.size());
		issues.stream().limit(5).forEach(i -> print("  #" + i.number(), i.state() + " " + i.title()
				+ " labels=" + i.labels().stream().map(Label::name).toList()
				+ " assignees=" + i.assignees().stream().map(User::login).toList()));
		assertThat(issues).noneMatch(Issue::isPullRequest);
	}

	@Test
	void readsOneIssueWithCommentsTimelineAndLinks() {
		GitHubReader reader = clients.readerFor(installationId);
		List<Issue> issues = reader.listIssues(repo, IssueStateFilter.ALL, null);
		assumeTrue(!issues.isEmpty(), "the repository has no issues");
		// prefer an issue that has comments, so the interesting calls return something
		Issue chosen = issues.stream().filter(i -> i.comments() > 0).findFirst().orElse(issues.get(0));

		Issue issue = reader.getIssue(repo, chosen.number());
		List<Comment> comments = reader.listComments(repo, issue.number());
		List<TimelineEvent> timeline = reader.getTimeline(repo, issue.number());
		List<IssueLink> blockedBy = reader.listBlockedBy(repo, issue.number());
		List<IssueLink> blocking = reader.listBlocking(repo, issue.number());
		List<IssueLink> subIssues = reader.listSubIssues(repo, issue.number());

		print("issue", "#" + issue.number() + " " + issue.title());
		print("comments", comments.size());
		print("timeline events", timeline.stream().map(TimelineEvent::event).toList());
		print("blocked by", blockedBy.stream().map(IssueLink::number).toList());
		print("blocking", blocking.stream().map(IssueLink::number).toList());
		print("sub-issues", subIssues.stream().map(IssueLink::number).toList());
		assertThat(issue.number()).isEqualTo(chosen.number());
		assertThat(comments).hasSize(issue.comments());
	}

	@Test
	void readsTheBoardWithTheCurrentIteration() {
		String project = config.get("LIVE_GITHUB_PROJECT");
		assumeTrue(project != null, "LIVE_GITHUB_PROJECT is not set: board part skipped");
		GitHubProjectsReader projects = clients.projectsReaderFor(installationId);
		ProjectRef board = new ProjectRef(repo.owner(), Integer.parseInt(project));

		List<ProjectSummary> boards = projects.listProjects(repo.owner());
		List<ProjectItem> items = projects.listItems(board);
		List<Iteration> iterations = projects.listIterations(board);
		Optional<Iteration> current = projects.currentIteration(board, LocalDate.now());

		print("boards of " + repo.owner(), boards.stream().map(b -> b.number() + " " + b.title()).toList());
		print("board items (issues)", items.size());
		items.stream().limit(5).forEach(i -> print("  #" + i.number(), i.repositoryFullName() + " status="
				+ i.status() + " iteration=" + i.iterationId()));
		print("iterations", iterations.stream().map(i -> i.title() + " " + i.startDate() + ".." + i.endDate()).toList());
		print("current iteration", current.map(Iteration::title).orElse("none today"));
		assertThat(boards).extracting(ProjectSummary::number).contains(board.number());
	}

	private static void print(String what, Object value) {
		System.out.println("[live] " + what + ": " + value);
	}
}
