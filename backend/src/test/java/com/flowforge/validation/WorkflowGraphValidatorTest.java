package com.flowforge.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.exception.InvalidExecutionRequestException;
import com.flowforge.executor.LogMessageExecutor;
import com.flowforge.executor.ManualTriggerExecutor;
import com.flowforge.executor.IfConditionExecutor;
import com.flowforge.executor.NodeExecutorRegistry;
import com.flowforge.executor.SetDataExecutor;
import com.flowforge.runtime.WorkflowGraphPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowGraphValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowGraphValidator validator = new WorkflowGraphValidator(new NodeExecutorRegistry(List.of(
            new ManualTriggerExecutor(),
            new SetDataExecutor(),
            new LogMessageExecutor(),
            new IfConditionExecutor()
    )));

    @Test
    void derivesExecutionOrderFromEdgesAndSupportsFanOut() throws Exception {
        JsonNode definition = objectMapper.readTree("""
                {
                  "nodes":[
                    {"id":"log","type":"log-message"},
                    {"id":"set-b","type":"set-data"},
                    {"id":"trigger","type":"manual-trigger"},
                    {"id":"set-a","type":"set-data"}
                  ],
                  "connections":[
                    {"id":"edge-a","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"set-a","targetHandle":"input"},
                    {"id":"edge-b","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"set-b","targetHandle":"input"},
                    {"id":"edge-c","sourceNodeId":"set-a","sourceHandle":"output","targetNodeId":"log","targetHandle":"input"}
                  ]
                }
                """);

        WorkflowGraphPlan graph = validator.validate(definition);

        assertThat(graph.triggerNodeId()).isEqualTo("trigger");
        assertThat(graph.executionOrder()).extracting(WorkflowGraphPlan.Node::id)
                .containsExactly("trigger", "set-a", "set-b", "log");
    }

    @Test
    void rejectsMissingReferencesInvalidHandlesDuplicateEdgesCyclesAndMissingTrigger() throws Exception {
        assertInvalid("""
                {"nodes":[{"id":"trigger","type":"manual-trigger"},{"id":"next","type":"set-data"}],
                 "connections":[{"id":"bad","sourceNodeId":"missing","sourceHandle":"output","targetNodeId":"next","targetHandle":"input"}]}
                """, "references missing node");
        assertInvalid("""
                {"nodes":[{"id":"trigger","type":"manual-trigger"},{"id":"next","type":"set-data"}],
                 "connections":[{"id":"bad","sourceNodeId":"trigger","sourceHandle":"out","targetNodeId":"next","targetHandle":"input"}]}
                """, "unsupported source handle");
        assertInvalid("""
                {"nodes":[{"id":"trigger","type":"manual-trigger"},{"id":"next","type":"set-data"}],
                 "connections":[{"id":"branch","sourceNodeId":"trigger","sourceHandle":"output-2","targetNodeId":"next","targetHandle":"input"}]}
                """, "unsupported source handle 'output-2'");
        assertInvalid("""
                {"nodes":[{"id":"trigger","type":"manual-trigger"}],
                 "connections":[{"id":"self","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"trigger","targetHandle":"input"}]}
                """, "cannot connect node 'trigger' to itself");
        assertInvalid("""
                {"nodes":[{"id":"trigger","type":"manual-trigger"},{"id":"trigger","type":"set-data"}],"connections":[]}
                """, "Duplicate node ID 'trigger'");
        assertInvalid("""
                {"nodes":[{"id":"trigger","type":"manual-trigger"},{"id":"next","type":"set-data"}],
                 "connections":[
                   {"id":"same","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"next","targetHandle":"input"},
                   {"id":"same","sourceNodeId":"trigger","sourceHandle":"output-2","targetNodeId":"next","targetHandle":"input"}]}
                """, "Duplicate connection ID 'same'");
        assertInvalid("""
                {"nodes":[{"id":"trigger","type":"manual-trigger"},{"id":"next","type":"set-data"}],
                 "connections":[
                   {"id":"edge-1","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"next","targetHandle":"input"},
                   {"id":"edge-2","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"next","targetHandle":"input"}]}
                """, "Duplicate connection from node");
        assertInvalid("""
                {"nodes":[{"id":"trigger","type":"manual-trigger"},{"id":"a","type":"set-data"},{"id":"b","type":"set-data"}],
                 "connections":[
                   {"id":"edge-1","sourceNodeId":"a","sourceHandle":"output","targetNodeId":"b","targetHandle":"input"},
                   {"id":"edge-2","sourceNodeId":"b","sourceHandle":"output","targetNodeId":"a","targetHandle":"input"}]}
                """, "contains a cycle");
        assertInvalid("""
                {"nodes":[{"id":"not-a-trigger","type":"set-data"}],"connections":[]}
                """, "must contain one manual-trigger");
        assertInvalid("""
                {"nodes":[{"id":"trigger","type":"manual-trigger"},{"id":"unknown","type":"http-request"}],"connections":[
                  {"id":"edge","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"unknown","targetHandle":"input"}]}
                """, "unsupported type 'http-request'");
        JsonNode multiInputDefinition = objectMapper.readTree("""
                {"nodes":[{"id":"trigger","type":"manual-trigger"},{"id":"join","type":"set-data"},{"id":"other","type":"set-data"}],
                 "connections":[
                   {"id":"edge-1","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"join","targetHandle":"input"},
                   {"id":"edge-2","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"other","targetHandle":"input"},
                   {"id":"edge-3","sourceNodeId":"other","sourceHandle":"output","targetNodeId":"join","targetHandle":"input"}]}
                """);
        assertThat(validator.validate(multiInputDefinition).incomingConnections().get("join"))
                .hasSize(2);
    }

    @Test
    void reportsUnreachableNodesAndInvalidTargetHandle() throws Exception {
        assertInvalid("""
                {"nodes":[{"id":"trigger","type":"manual-trigger"},{"id":"orphan","type":"set-data"}],"connections":[]}
                """, "unreachable nodes: orphan");
        assertInvalid("""
                {"nodes":[{"id":"trigger","type":"manual-trigger"},{"id":"next","type":"set-data"}],
                 "connections":[{"id":"edge","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"next","targetHandle":"in"}]}
                """, "unsupported target handle 'in'");
    }

    @Test
    void acceptsConditionalBranchesAndNormalizesOnlyLegacyConditionalHandles() throws Exception {
        JsonNode legacyDefinition = objectMapper.readTree("""
                {"nodes":[
                  {"id":"trigger","type":"manual-trigger"},
                  {"id":"condition","type":"if-condition","configuration":{"field":"active","operator":"equals","value":true}},
                  {"id":"yes","type":"log-message","configuration":{"message":"yes"}},
                  {"id":"no","type":"log-message","configuration":{"message":"no"}}
                ],
                "connections":[
                  {"id":"start","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"condition","targetHandle":"input"},
                  {"id":"legacy-yes","sourceNodeId":"condition","sourceHandle":"output","targetNodeId":"yes","targetHandle":"input"},
                  {"id":"legacy-no","sourceNodeId":"condition","sourceHandle":"output-2","targetNodeId":"no","targetHandle":"input"}
                ]}
                """);
        WorkflowGraphPlan graph = validator.validate(legacyDefinition);
        assertThat(graph.outgoingConnections().get("condition"))
                .extracting(WorkflowGraphPlan.Connection::sourceHandle)
                .containsExactly("true", "false");
        assertThat(legacyDefinition.path("connections").get(1).path("sourceHandle").asText())
                .isEqualTo("output");
        assertInvalid("""
                {"nodes":[{"id":"trigger","type":"manual-trigger"},
                  {"id":"condition","type":"if-condition","configuration":{"field":"status","operator":"equals","value":"active"}},
                  {"id":"yes","type":"log-message"}],
                 "connections":[
                   {"id":"start","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"condition","targetHandle":"input"},
                   {"id":"yes-edge","sourceNodeId":"condition","sourceHandle":"true","targetNodeId":"yes","targetHandle":"input"}]}
                """, "missing outgoing branch connection(s): false");
        assertInvalid("""
                {"nodes":[{"id":"trigger","type":"manual-trigger"},{"id":"next","type":"set-data"}],
                 "connections":[{"id":"bad","sourceNodeId":"trigger","sourceHandle":"output-2","targetNodeId":"next","targetHandle":"input"}]}
                """, "unsupported source handle 'output-2'");
        assertInvalid("""
                {"nodes":[{"id":"trigger","type":"manual-trigger"},
                  {"id":"condition","type":"if-condition","configuration":{"field":"age","operator":"greater than","value":true}},
                  {"id":"yes","type":"log-message"},{"id":"no","type":"log-message"}],
                 "connections":[
                   {"id":"start","sourceNodeId":"trigger","sourceHandle":"output","targetNodeId":"condition","targetHandle":"input"},
                   {"id":"yes","sourceNodeId":"condition","sourceHandle":"true","targetNodeId":"yes","targetHandle":"input"},
                   {"id":"no","sourceNodeId":"condition","sourceHandle":"false","targetNodeId":"no","targetHandle":"input"}]}
                """, "Ordering comparisons require a number or text value");
    }

    private void assertInvalid(String json, String message) throws Exception {
        JsonNode definition = objectMapper.readTree(json);
        assertThatThrownBy(() -> validator.validate(definition))
                .isInstanceOf(InvalidExecutionRequestException.class)
                .hasMessageContaining(message);
    }
}
