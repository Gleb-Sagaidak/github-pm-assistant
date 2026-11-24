package cz.cvut.pmassistant.llm.model;

/** Why the model stopped, normalized across providers. */
public enum StopReason {
	/** The model finished its turn. */
	END_TURN,
	/** The model wants tools to be run. */
	TOOL_USE,
	/** The output was cut at the token limit. */
	MAX_TOKENS,
	/** The model (or the provider's safety layer) declined to answer. */
	REFUSAL,
	/** Anything else the provider reports. */
	OTHER
}
