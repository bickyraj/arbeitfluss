package com.flowforge.repository;

import com.flowforge.entity.Execution;
import com.flowforge.model.execution.ExecutionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExecutionRepository extends JpaRepository<Execution, UUID> {
    Page<Execution> findAllByWorkflow_IdOrderByCreatedAtDesc(UUID workflowId, Pageable pageable);

    @Query("""
            select e from Execution e
            where (:status is null or e.status = :status)
              and (:search is null or lower(e.workflowName) like lower(concat('%', :search, '%')))
            """)
    Page<Execution> searchAll(
            @Param("status") ExecutionStatus status,
            @Param("search") String search,
            Pageable pageable
    );

    List<Execution> findAllByStatusIn(List<ExecutionStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Execution e where e.id = :id")
    Optional<Execution> findByIdForUpdate(@Param("id") UUID id);
}
