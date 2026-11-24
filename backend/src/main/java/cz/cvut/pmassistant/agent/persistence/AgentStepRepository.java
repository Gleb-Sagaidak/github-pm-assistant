package cz.cvut.pmassistant.agent.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Steps carry no workspace of their own; they are read through their run, so the workspace rule holds here too:
 * asking for the steps of a run in the wrong workspace gives nothing.
 */
public interface AgentStepRepository extends Repository<AgentStep, UUID> {

	AgentStep save(AgentStep step);

	@Query("""
			select s from AgentStep s, AgentRun r
			where s.runId = r.id and r.id = :runId and r.workspaceId = :workspaceId
			order by s.seq""")
	List<AgentStep> findStepsOfRun(@Param("runId") UUID runId, @Param("workspaceId") UUID workspaceId);
}
