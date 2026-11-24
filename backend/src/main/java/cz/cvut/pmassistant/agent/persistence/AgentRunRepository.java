package cz.cvut.pmassistant.agent.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * Runs, always asked for within a workspace. It extends the bare {@link Repository} on purpose: no
 * {@code findById(id)} without a workspace exists, so a run of another workspace cannot be reached by guessing
 * its id. An unknown or foreign id looks the same, as "not found".
 */
public interface AgentRunRepository extends Repository<AgentRun, UUID> {

	AgentRun save(AgentRun run);

	Optional<AgentRun> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

	/** The workspace's runs, newest first. */
	List<AgentRun> findByWorkspaceIdOrderByStartedAtDesc(UUID workspaceId, Pageable pageable);
}
