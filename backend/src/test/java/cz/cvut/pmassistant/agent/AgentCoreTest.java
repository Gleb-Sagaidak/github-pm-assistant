package cz.cvut.pmassistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import cz.cvut.pmassistant.github.RunScopedCache;
import cz.cvut.pmassistant.github.model.RepoRef;
import cz.cvut.pmassistant.llm.model.ToolSpec;
import cz.cvut.pmassistant.tenant.WorkspaceRef;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AgentCoreTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final JsonNode SCHEMA = JSON.readTree("{\"type\":\"object\",\"properties\":{}}");

	private static ToolSpec spec(String name) {
		return new ToolSpec(name, "does " + name, SCHEMA, true);
	}

	private static Tool tool(String name) {
		return new Tool() {
			@Override
			public ToolSpec spec() {
				return AgentCoreTest.spec(name);
			}

			@Override
			public ToolOutcome execute(JsonNode arguments, RunContext context) {
				return ToolOutcome.ok(name);
			}
		};
	}

	@Test
	void aToolOutcomeIsOkOrAnError() {
		assertThat(ToolOutcome.ok("fine").isError()).isFalse();
		assertThat(ToolOutcome.error("broken").isError()).isTrue();
		assertThat(ToolOutcome.error("broken").content()).isEqualTo("broken");
		assertThatThrownBy(() -> new ToolOutcome(null, false)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void theRegistryFindsToolsByNameAndKeepsTheirOrder() {
		ToolRegistry registry = new ToolRegistry(List.of(tool("get_issue"), tool("list_labels")));

		assertThat(registry.find("get_issue")).isPresent();
		assertThat(registry.find("unknown")).isEmpty();
		assertThat(registry.specs()).extracting(ToolSpec::name).containsExactly("get_issue", "list_labels");
		assertThat(registry.size()).isEqualTo(2);
	}

	@Test
	void theRegistryRefusesTwoToolsWithOneName() {
		assertThatThrownBy(() -> new ToolRegistry(List.of(tool("a"), tool("a"))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("a");
	}

	@Test
	void limitsMustBePositive() {
		assertThat(RunLimits.standard().maxSteps()).isPositive();
		assertThatThrownBy(() -> RunLimits.standard().withMaxSteps(0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RunLimits.standard().withMaxTotalTokens(0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RunLimits.standard().withMaxDuration(Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RunLimits.standard().withToolTimeout(Duration.ofSeconds(-1)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void limitsCanBeChangedOneAtATime() {
		RunLimits limits = RunLimits.standard().withMaxSteps(3).withMaxTotalTokens(500)
				.withMaxDuration(Duration.ofSeconds(10)).withToolTimeout(Duration.ofSeconds(2));

		assertThat(limits.maxSteps()).isEqualTo(3);
		assertThat(limits.maxTotalTokens()).isEqualTo(500);
		assertThat(limits.maxDuration()).isEqualTo(Duration.ofSeconds(10));
		assertThat(limits.toolTimeout()).isEqualTo(Duration.ofSeconds(2));
		assertThat(limits.maxOutputTokens()).isEqualTo(RunLimits.standard().maxOutputTokens());
	}

	@Test
	void anAgentOffersItsReadToolsAndThenTheTerminalTool() {
		AgentDefinition agent = new AgentDefinition("triage", "You triage issues.",
				List.of(tool("get_issue"), tool("list_labels")), spec("submit_triage_proposal"),
				RunLimits.standard());

		assertThat(agent.allSpecs()).extracting(ToolSpec::name)
				.containsExactly("get_issue", "list_labels", "submit_triage_proposal");
		assertThat(agent.registry().find("submit_triage_proposal")).isEmpty();
	}

	@Test
	void theTerminalToolMustNotAlsoBeAReadTool() {
		assertThatThrownBy(() -> new AgentDefinition("triage", "p", List.of(tool("submit")), spec("submit"),
				RunLimits.standard())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void anAgentDefinitionKeepsAnImmutableCopyOfItsTools() {
		List<Tool> tools = new java.util.ArrayList<>(List.of(tool("a")));
		AgentDefinition agent = new AgentDefinition("x", "p", tools, spec("done"), RunLimits.standard());
		tools.add(tool("b"));

		assertThat(agent.tools()).hasSize(1);
	}

	@Test
	void aNewRunGetsItsOwnIdAndEmptyCache() {
		WorkspaceRef workspace = new WorkspaceRef(UUID.randomUUID(), 11, Set.of(), null);
		Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

		RunContext first = RunContext.forNewRun(workspace, clock);
		RunContext second = RunContext.forNewRun(workspace, clock);

		assertThat(first.runId()).isNotEqualTo(second.runId());
		assertThat(first.cache()).isNotSameAs(second.cache());
		assertThat(first.cache().size()).isZero();
		assertThat(first.workspace()).isSameAs(workspace);
	}

	@Test
	void aRunContextNeedsEveryPart() {
		WorkspaceRef workspace = new WorkspaceRef(UUID.randomUUID(), 11, Set.of(), null);
		Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

		assertThatThrownBy(() -> new RunContext(null, workspace, new RunScopedCache(), clock))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new RunContext(UUID.randomUUID(), null, new RunScopedCache(), clock))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void resultsKeepWhatTheyAreGiven() {
		JsonNode answer = JSON.readTree("{\"category\":\"bug\"}");

		assertThat(new AgentRunResult.Completed(answer).finalArguments()).isEqualTo(answer);
		AgentRunResult.Failed failed = new AgentRunResult.Failed(FailureReason.STEP_LIMIT, "15 steps");
		assertThat(failed.reason()).isEqualTo(FailureReason.STEP_LIMIT);
		assertThat(failed.detail()).isEqualTo("15 steps");
	}

	@Test
	void aWorkspaceKnowsItsRepositoriesByNumericId() {
		RepoRef renamed = new RepoRef(42, "cvut-team", "renamed-repo");
		WorkspaceRef workspace = new WorkspaceRef(UUID.randomUUID(), 11,
				Set.of(new RepoRef(42, "cvut-team", "old-name"), new RepoRef(43, "cvut-team", "docs")), null);

		assertThat(workspace.containsRepo(renamed)).as("same id after a rename").isTrue();
		assertThat(workspace.containsRepo(new RepoRef(99, "cvut-team", "old-name")))
				.as("same name, other id").isFalse();
	}

	@Test
	void aWorkspaceKeepsAnImmutableCopyOfItsRepositories() {
		java.util.Set<RepoRef> repos = new java.util.HashSet<>(Set.of(new RepoRef(1, "o", "a")));
		WorkspaceRef workspace = new WorkspaceRef(UUID.randomUUID(), 11, repos, null);
		repos.add(new RepoRef(2, "o", "b"));

		assertThat(workspace.repos()).hasSize(1);
		assertThatThrownBy(() -> new WorkspaceRef(null, 1, Set.of(), null)).isInstanceOf(NullPointerException.class);
	}
}
