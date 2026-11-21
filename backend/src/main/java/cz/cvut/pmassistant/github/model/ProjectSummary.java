package cz.cvut.pmassistant.github.model;

/** A board as shown in the "choose a board" step of onboarding. */
public record ProjectSummary(String nodeId, int number, String title) {
}
