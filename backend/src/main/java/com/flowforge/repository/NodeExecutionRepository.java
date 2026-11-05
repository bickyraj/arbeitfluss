package com.flowforge.repository;

import com.flowforge.entity.NodeExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NodeExecutionRepository extends JpaRepository<NodeExecution, UUID> {
    List<NodeExecution> findAllByExecution_IdOrderByCreatedAtAscWorkflowNodeIdAsc(UUID executionId);
    java.util.Optional<NodeExecution> findByExecution_IdAndWorkflowNodeId(UUID executionId, String workflowNodeId);
    List<NodeExecution> findAllByExecution_Id(UUID executionId);
}
