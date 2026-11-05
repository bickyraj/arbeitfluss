package com.flowforge.repository;

import com.flowforge.entity.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {
    @Transactional(readOnly = true)
    @Query("select workflow.id from Workflow workflow order by workflow.id")
    List<UUID> findAllIdsForRegistrationSync();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select workflow from Workflow workflow where workflow.id = :id")
    Optional<Workflow> findByIdForRegistrationUpdate(@Param("id") UUID id);
}
