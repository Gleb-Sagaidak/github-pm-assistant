package cz.cvut.pmassistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import cz.cvut.pmassistant.llm.LlmClient;
import cz.cvut.pmassistant.llm.LlmUnavailableException;
import cz.cvut.pmassistant.llm.model.ContentPart;
import cz.cvut.pmassistant.llm.model.LlmMessage;
import cz.cvut.pmassistant.llm.model.LlmRequest;
import cz.cvut.pmassistant.llm.model.LlmResponse;
import cz.cvut.pmassistant.llm.model.StopReason;
import cz.cvut.pmassistant.llm.model.ToolSpec;
import cz.cvut.pmassistant.llm.model.Usage;

import tools.jackson.databind.JsonNode;

/**
 * The ReAct loop: ask the model, run the tools it asks for, give it the results, repeat until it calls the
 * agent's terminal tool. Our own loop (not a framework) so every limit is enforced and every step recorded.
 * <p>
 * The terminal tool is the agent's only way to act: everything else it can call is a read. A run that does not
 * end with a valid terminal call is a failure and produces nothing.
 */
public class AgentLoop {

	private final LlmClient llm;
	private final AgentRunRecorder recorder;

	public AgentLoop(LlmClient llm, AgentRunRecorder recorder) {
		this.llm = llm;
		this.recorder = recorder;
	}

	/**
	 * Runs the agent to the end. Expected problems (limits, refusal, unavailable model) come back as
	 * {@link AgentRunResult.Failed}; an unexpected exception is recorded as a failed run and rethrown.
	 */
	public AgentRunResult run(AgentRunRequest request, RunContext context) {
		return new Execution(request, context).execute();
	}

	/** The state of one run. */
	private final class Execution {

		private final AgentRunRequest request;
		private final AgentDefinition agent;
		private final RunContext context;
		private final RunLimits limits;
		private final ToolRegistry registry;
		private final List<ToolSpec> specs;
		private final List<LlmMessage> messages = new ArrayList<>();
		private Usage usage = Usage.ZERO;
		private int seq;
		private int llmSteps;
		private int reminders;
		private int invalidFinalAnswers;
		private final int repeatedCalls = 0;
		private final Instant startedAt;
		private final ArgumentValidator finalAnswerValidator;

		Execution(AgentRunRequest request, RunContext context) {
			this.request = request;
			this.agent = request.agent();
			this.context = context;
			this.limits = agent.limits();
			this.registry = agent.registry();
			this.specs = agent.allSpecs();
			this.startedAt = context.clock().instant();
			this.finalAnswerValidator = new ArgumentValidator(agent.terminalTool().inputSchema());
		}

		AgentRunResult execute() {
			recorder.started(new AgentRunRecorder.RunStart(context.runId(), context.workspace().workspaceId(),
					request.githubRepoId(), request.triggeredBy(), agent.name(), request.trigger(), request.input(),
					llm.model(), now()));
			AgentRunResult result;
			try {
				messages.add(LlmMessage.user(request.userMessage()));
				result = loop();
			}
			catch (RuntimeException unexpected) {
				finish(new AgentRunResult.Failed(FailureReason.INTERNAL_ERROR, String.valueOf(unexpected)));
				throw unexpected;
			}
			finish(result);
			return result;
		}

		private AgentRunResult loop() {
			for (int step = 1; step <= limits.maxSteps(); step++) {
				Duration elapsed = Duration.between(startedAt, now());
				if (elapsed.compareTo(limits.maxDuration()) > 0) {
					return fail(FailureReason.TIME_LIMIT, "The run took " + elapsed.toSeconds()
							+ " s, the limit is " + limits.maxDuration().toSeconds() + " s");
				}
				Instant callStarted = now();
				LlmResponse response;
				try {
					response = llm.chat(new LlmRequest(agent.systemPrompt(), messages, specs, limits.maxOutputTokens()));
				}
				catch (LlmUnavailableException e) {
					return fail(FailureReason.LLM_UNAVAILABLE, e.reason() + ": " + e.getMessage());
				}
				llmSteps++;
				usage = usage.plus(response.usage());
				recorder.llmStep(context.runId(), new AgentRunRecorder.LlmStep(++seq, response.usage(),
						Duration.between(callStarted, now()), response.text()));
				messages.add(assistantMessage(response));

				// Checked before anything the response asks for is done: a run over budget stops here.
				if (usage.totalTokens() > limits.maxTotalTokens()) {
					return fail(FailureReason.TOKEN_LIMIT, "The run used " + usage.totalTokens()
							+ " tokens, the limit is " + limits.maxTotalTokens());
				}
				if (response.stopReason() == StopReason.REFUSAL) {
					return fail(FailureReason.REFUSAL, "The model declined to answer");
				}
				if (response.stopReason() == StopReason.MAX_TOKENS) {
					return fail(FailureReason.MAX_TOKENS, "The answer was cut off at " + limits.maxOutputTokens()
							+ " tokens");
				}

				List<ContentPart.ToolCall> calls = response.toolCalls();
				if (calls.isEmpty()) {
					Optional<AgentRunResult> failure = remindAboutFinalAnswer();
					if (failure.isPresent()) {
						return failure.get();
					}
					continue;
				}
				Optional<AgentRunResult> ended = handleToolCalls(calls);
				if (ended.isPresent()) {
					return ended.get();
				}
			}
			return fail(FailureReason.STEP_LIMIT, "No final answer after " + limits.maxSteps() + " steps");
		}

		/** The model stopped without calling anything: one reminder, then the run fails. */
		private Optional<AgentRunResult> remindAboutFinalAnswer() {
			if (reminders >= 1) {
				return Optional.of(fail(FailureReason.NO_FINAL_ANSWER,
						"The model ended its turn twice without calling " + agent.terminalTool().name()));
			}
			reminders++;
			messages.add(LlmMessage.user("You have not submitted your result yet. Finish by calling the tool "
					+ agent.terminalTool().name() + "."));
			return Optional.empty();
		}

		/**
		 * Runs the calls of one assistant turn in order and answers all of them in ONE message. Returns a
		 * result if the run ends here (the terminal tool was called).
		 */
		private Optional<AgentRunResult> handleToolCalls(List<ContentPart.ToolCall> calls) {
			List<ContentPart.ToolResult> results = new ArrayList<>();
			for (ContentPart.ToolCall call : calls) {
				if (call.name().equals(agent.terminalTool().name())) {
					Optional<AgentRunResult> ended = handleFinalAnswer(call, results);
					if (ended.isPresent()) {
						return ended;
					}
					continue;
				}
				results.add(runReadTool(call));
			}
			messages.add(LlmMessage.toolResults(results));
			return Optional.empty();
		}

		/**
		 * The terminal call ends the run if its arguments match the schema. If not, the model is told what is
		 * wrong and gets one more chance (the first line of the NFR7 defence); a second invalid answer fails
		 * the run.
		 */
		private Optional<AgentRunResult> handleFinalAnswer(ContentPart.ToolCall call,
				List<ContentPart.ToolResult> results) {
			List<String> problems = finalAnswerValidator.validate(call.arguments());
			if (problems.isEmpty()) {
				return Optional.of(new AgentRunResult.Completed(call.arguments()));
			}
			invalidFinalAnswers++;
			String description = String.join("; ", problems);
			String message = "The arguments of " + call.name() + " do not match its schema: " + description
					+ ". Call the tool again with corrected arguments.";
			recorder.toolStep(context.runId(), new AgentRunRecorder.ToolStep(++seq, call.name(), call.arguments(),
					message, true, Duration.ZERO));
			if (invalidFinalAnswers >= 2) {
				return Optional.of(fail(FailureReason.INVALID_FINAL_ANSWER,
						"The final answer was invalid twice: " + description));
			}
			results.add(new ContentPart.ToolResult(call.id(), message, true));
			return Optional.empty();
		}

		private ContentPart.ToolResult runReadTool(ContentPart.ToolCall call) {
			Instant toolStarted = now();
			ToolOutcome outcome;
			Optional<Tool> tool = registry.find(call.name());
			if (tool.isEmpty()) {
				outcome = ToolOutcome.error("Unknown tool: " + call.name() + ". Available tools: "
						+ String.join(", ", registry.specs().stream().map(ToolSpec::name).toList())
						+ ", " + agent.terminalTool().name());
			}
			else {
				outcome = execute(tool.get(), call.arguments());
			}
			recorder.toolStep(context.runId(), new AgentRunRecorder.ToolStep(++seq, call.name(), call.arguments(),
					outcome.content(), outcome.isError(), Duration.between(toolStarted, now())));
			return new ContentPart.ToolResult(call.id(), outcome.content(), outcome.isError());
		}

		/** A tool that throws does not fail the run: the model is told, and may do without it. */
		private ToolOutcome execute(Tool tool, JsonNode arguments) {
			try {
				return tool.execute(arguments, context);
			}
			catch (RuntimeException e) {
				return ToolOutcome.error("Tool " + tool.name() + " failed: " + e.getClass().getSimpleName() + ": "
						+ e.getMessage());
			}
		}

		/** Providers reject empty messages, so a response with no content gets a placeholder. */
		private LlmMessage assistantMessage(LlmResponse response) {
			if (response.parts().isEmpty()) {
				return LlmMessage.assistant(List.of(new ContentPart.Text("(no content)")));
			}
			return LlmMessage.assistant(response.parts());
		}

		private AgentRunResult fail(FailureReason reason, String detail) {
			return new AgentRunResult.Failed(reason, detail);
		}

		private void finish(AgentRunResult result) {
			recorder.finished(context.runId(),
					new AgentRunRecorder.RunFinish(now(), result, llmSteps, usage, repeatedCalls));
		}

		private Instant now() {
			return context.clock().instant();
		}
	}
}
