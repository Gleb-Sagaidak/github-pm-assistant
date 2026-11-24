package cz.cvut.pmassistant.agent;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import cz.cvut.pmassistant.github.RunScopedCache;
import cz.cvut.pmassistant.tenant.WorkspaceRef;

/**
 * Everything a tool may know about the run it belongs to. The clock is injected so tests can move time.
 *
 * @param cache remembers GitHub answers for this run only (see {@link RunScopedCache})
 */
public record RunContext(UUID runId, WorkspaceRef workspace, RunScopedCache cache, Clock clock) {

	public RunContext {
		Objects.requireNonNull(runId, "runId");
		Objects.requireNonNull(workspace, "workspace");
		Objects.requireNonNull(cache, "cache");
		Objects.requireNonNull(clock, "clock");
	}

	/** A context for a new run with its own empty cache. */
	public static RunContext forNewRun(WorkspaceRef workspace, Clock clock) {
		return new RunContext(UUID.randomUUID(), workspace, new RunScopedCache(), clock);
	}
}
