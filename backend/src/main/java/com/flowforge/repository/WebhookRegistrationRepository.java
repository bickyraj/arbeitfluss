package com.flowforge.repository;

import com.flowforge.entity.WebhookRegistration;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookRegistrationRepository extends JpaRepository<WebhookRegistration, UUID> {
    @EntityGraph(attributePaths = "workflow")
    Optional<WebhookRegistration> findByWebhookId(String webhookId);
    Optional<WebhookRegistration> findByWorkflow_IdAndNodeId(UUID workflowId, String nodeId);
    List<WebhookRegistration> findAllByWorkflow_Id(UUID workflowId);
    void deleteAllByWorkflow_Id(UUID workflowId);
}
