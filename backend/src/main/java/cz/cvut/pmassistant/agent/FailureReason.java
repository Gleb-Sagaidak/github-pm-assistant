package cz.cvut.pmassistant.agent;

/** Why a run failed. A failed run never produces a proposal. */
public enum FailureReason {
	/** The model declined to answer. */
	REFUSAL,
	/** An answer was cut off at the output token limit. */
	MAX_TOKENS,
	/** The model ended its turn without calling the terminal tool, even after a reminder. */
	NO_FINAL_ANSWER,
	/** The terminal tool's arguments did not match its schema, even after a second chance. */
	INVALID_FINAL_ANSWER,
	STEP_LIMIT,
	TIME_LIMIT,
	TOKEN_LIMIT,
	/** The model could not be used (see {@link cz.cvut.pmassistant.llm.LlmUnavailableException}). */
	LLM_UNAVAILABLE
}
