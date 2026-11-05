package com.flowforge.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowforge.model.execution.ExecutionLogLevel;
import com.flowforge.runtime.ExecutionContext;
import org.springframework.stereotype.Component;

@Component
public class IfConditionExecutor implements NodeExecutor {
    private final ConditionEvaluator evaluator;

    public IfConditionExecutor() {
        this(new ConditionEvaluator());
    }

    IfConditionExecutor(ConditionEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public String nodeType() {
        return "if-condition";
    }

    @Override
    public void validateConfiguration(JsonNode configuration) {
        evaluator.validateConfiguration(configuration);
    }

    @Override
    public JsonNode execute(JsonNode input, ExecutionContext context) {
        return input == null ? JsonNodeFactory.instance.nullNode() : input.deepCopy();
    }

    @Override
    public NodeExecutionResult executeResult(JsonNode input, ExecutionContext context) {
        ConditionEvaluator.Evaluation evaluation = evaluator.evaluate(context.nodeConfiguration(), input);
        String selectedHandle = evaluation.selectedBranch();
        ObjectNode details = JsonNodeFactory.instance.objectNode();
        details.put("nodeType", nodeType());
        details.put("field", evaluation.field());
        details.put("operator", evaluation.operator());
        details.put("result", evaluation.result());
        details.put("selectedBranch", evaluation.selectedBranch().toUpperCase());
        context.log(
                ExecutionLogLevel.INFO,
                "Condition selected the " + evaluation.selectedBranch().toUpperCase() + " branch.",
                details
        );
        return new NodeExecutionResult(
                input == null ? JsonNodeFactory.instance.nullNode() : input.deepCopy(),
                selectedHandle,
                details
        );
    }
}
