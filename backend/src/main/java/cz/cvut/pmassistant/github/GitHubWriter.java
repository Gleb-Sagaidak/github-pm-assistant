package cz.cvut.pmassistant.github;

/**
 * Write access to GitHub. Declared in stage 1 so that the read/write split exists from the start; the methods
 * (addLabels, removeLabel, addAssignees, addBlockedBy) and the implementation arrive in stage 4.
 * <p>
 * Only {@code ActionExecutor} may depend on this interface; it must not be injected anywhere else.
 */
public interface GitHubWriter {
}
