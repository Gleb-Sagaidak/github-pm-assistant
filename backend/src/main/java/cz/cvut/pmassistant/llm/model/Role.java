package cz.cvut.pmassistant.llm.model;

/**
 * Who wrote a message. Tool results travel in a {@code USER} message, as most providers expect; an adapter for
 * a provider with a separate tool role translates.
 */
public enum Role {
	USER, ASSISTANT
}
