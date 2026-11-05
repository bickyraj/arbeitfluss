package com.flowforge.repository;

import com.flowforge.entity.ExecutionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExecutionLogRepository extends JpaRepository<ExecutionLog, UUID> {
    List<ExecutionLog> findAllByExecution_IdOrderByCreatedAtAscIdAsc(UUID executionId);
}
