package com.flowforge.repository;

import com.flowforge.entity.WebhookRegistration;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookRegistrationRepository extends JpaRepository<WebhookRegistration, UUID> {
    @Query("select registration.workflow.id from WebhookRegistration registration where registration.webhookId = :webhookId")
    Optional<UUID> findWorkflowIdForIngress(@Param("webhookId") String webhookId);
    @EntityGraph(attributePaths = "workflow")
    Optional<WebhookRegistration> findByWebhookId(String webhookId);
    Optional<WebhookRegistration> findByWorkflow_IdAndNodeId(UUID workflowId, String nodeId);
    List<WebhookRegistration> findAllByWorkflow_Id(UUID workflowId);
    void deleteAllByWorkflow_Id(UUID workflowId);
}
