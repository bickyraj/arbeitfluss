package com.flowforge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.dto.workflow.CreateWorkflowRequest;
import com.flowforge.dto.workflow.UpdateWorkflowRequest;
import com.flowforge.entity.Workflow;
import com.flowforge.repository.WebhookRegistrationRepository;
import com.flowforge.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class WebhookRegistrationStartupSyncTest {
    @MockitoSpyBean private WorkflowRepository workflows;
    @Autowired private WebhookRegistrationRepository registrations;
    @MockitoSpyBean private WebhookRegistrationService registrationService;
    @Autowired private WebhookRegistrationStartupSync startupSync;
    @Autowired private WorkflowService workflowService;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void startupUsesParentLockAndPreservesExistingPublicIdAndSecretHash() {
        UUID id = create("secret-token");
        registrationService.regenerateSecret(id, "hook");
        var before = registrations.findByWorkflow_IdAndNodeId(id, "hook").orElseThrow();
        String publicId = before.getWebhookId();
        String hash = before.getSecretHash();
        doReturn(List.of(id)).when(workflows).findAllIdsForRegistrationSync();
        clearInvocations(workflows);

        startupSync.synchronizeSavedWorkflows();

        verify(workflows).findByIdForRegistrationUpdate(id);
        verify(workflows, never()).findAll();
        assertThat(AopUtils.isAopProxy(registrationService)).isTrue();
        var after = registrations.findByWorkflow_IdAndNodeId(id, "hook").orElseThrow();
        assertThat(after.getWebhookId()).isEqualTo(publicId);
        assertThat(hash.equals(after.getSecretHash())).isTrue();
    }

    @Test
    void workflowDisabledAfterIdDiscoveryRemainsDisabledOnLockedReread() {
        UUID id = create("none");
        List<UUID> discovered = workflows.findAllIdsForRegistrationSync().stream().filter(id::equals).toList();
        doReturn(discovered).when(workflows).findAllIdsForRegistrationSync();
        workflowService.updateWorkflow(id, new UpdateWorkflowRequest(null, null, false, null));
        clearInvocations(workflows);

        startupSync.synchronizeSavedWorkflows();

        verify(workflows).findByIdForRegistrationUpdate(id);
        assertThat(workflows.findById(id).orElseThrow().isActive()).isFalse();
        assertThat(registrations.findByWorkflow_IdAndNodeId(id, "hook").orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void malformedWorkflowFailureIsIsolatedAndHealthyWorkflowCommitsDespiteCallerRollback() {
        UUID malformed = workflows.saveAndFlush(new Workflow(
                "Malformed legacy fixture", "", true, mapper.createObjectNode().putNull("nodes")
        )).getId();
        UUID healthy = workflows.saveAndFlush(new Workflow("Healthy fixture", "", true, definition("none"))).getId();
        doReturn(List.of(malformed, healthy)).when(workflows).findAllIdsForRegistrationSync();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                startupSync.synchronizeSavedWorkflows();
                status.setRollbackOnly();
            });

            verify(workflows).findByIdForRegistrationUpdate(malformed);
            verify(workflows).findByIdForRegistrationUpdate(healthy);
            assertThat(registrations.findAllByWorkflow_Id(malformed)).isEmpty();
            assertThat(registrations.findByWorkflow_IdAndNodeId(healthy, "hook").orElseThrow().isEnabled()).isTrue();
            assertThat(workflows.findById(malformed).orElseThrow().getDefinition().path("nodes").isNull()).isTrue();
        } finally {
            workflows.deleteById(malformed);
        }
    }

    @Test
    void workflowDeletedAfterDiscoveryIsSafelySkipped() {
        UUID removed = UUID.randomUUID();
        doReturn(List.of(removed)).when(workflows).findAllIdsForRegistrationSync();
        startupSync.synchronizeSavedWorkflows();
        verify(workflows).findByIdForRegistrationUpdate(removed);
        assertThat(registrations.findAllByWorkflow_Id(removed)).isEmpty();
    }

    @Test
    void startupWaitsForConcurrentSaveAndReconcilesCommittedDisabledState() throws Exception {
        UUID id = create("none");
        doReturn(List.of(id)).when(workflows).findAllIdsForRegistrationSync();
        CountDownLatch writerHasLock = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch startupReachedLock = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("startup-reconciliation-test")) {
                startupReachedLock.countDown();
            }
            return invocation.callRealMethod();
        }).when(registrationService).synchronizeSavedWorkflow(id);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Workflow workflow = workflows.findByIdForRegistrationUpdate(id).orElseThrow();
                workflow.setActive(false);
                workflows.saveAndFlush(workflow);
                writerHasLock.countDown();
                await(releaseWriter);
            }));
            try {
                assertThat(writerHasLock.await(5, TimeUnit.SECONDS)).isTrue();
                var reconciliation = executor.submit(() -> {
                    Thread.currentThread().setName("startup-reconciliation-test");
                    startupSync.synchronizeSavedWorkflows();
                });
                assertThat(startupReachedLock.await(5, TimeUnit.SECONDS)).isTrue();
                org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> reconciliation.get(200, TimeUnit.MILLISECONDS));
                releaseWriter.countDown();
                writer.get(10, TimeUnit.SECONDS);
                reconciliation.get(10, TimeUnit.SECONDS);
                verify(workflows, times(2)).findByIdForRegistrationUpdate(id);
                assertThat(workflows.findById(id).orElseThrow().isActive()).isFalse();
                assertThat(registrations.findByWorkflow_IdAndNodeId(id, "hook").orElseThrow().isEnabled()).isFalse();
            } finally {
                releaseWriter.countDown();
            }
        }
    }

    private UUID create(String authentication) {
        return workflowService.createWorkflow(new CreateWorkflowRequest(
                "Startup registration test", "", true, definition(authentication)
        )).id();
    }

    private ObjectNode definition(String authentication) {
        ObjectNode definition = mapper.createObjectNode();
        definition.putArray("nodes").addObject().put("id", "hook").put("type", "webhook-trigger")
                .putObject("configuration").put("method", "POST").put("authentication", authentication).put("active", true);
        definition.putArray("connections");
        return definition;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test synchronization timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test synchronization interrupted");
        }
    }
}
