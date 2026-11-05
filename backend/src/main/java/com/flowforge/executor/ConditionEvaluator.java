package com.flowforge.executor;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

public class ConditionEvaluator {
    private static final Set<String> OPERATORS = Set.of(
            "equals",
            "not equals",
            "greater than",
            "greater than or equal",
            "less than",
            "less than or equal",
            "is empty",
            "is not empty"
    );

    public Evaluation evaluate(JsonNode configuration, JsonNode input) {
        validateConfiguration(configuration);
        String field = requiredText(configuration, "field", "Condition requires a non-empty field");
        String operator = requiredText(configuration, "operator", "Condition requires an operator");

        JsonNode actual = resolveField(input, field);
        JsonNode expected = configuration.get("value");
        boolean result;
        if ("is empty".equals(operator) || "is not empty".equals(operator)) {
            if (actual != null && !actual.isNull() && !actual.isValueNode()) {
                throw new NodeExecutionException("Condition field must contain a scalar value.");
            }
            boolean empty = actual == null || actual.isNull() || actual.textValue() != null && actual.textValue().isEmpty();
            result = "is empty".equals(operator) == empty;
        } else {
            if (actual == null) {
                throw new NodeExecutionException("Condition field '" + field + "' is missing.");
            }
            if (expected == null) {
                throw new NodeExecutionException("Condition requires a comparison value.");
            }
            if (actual.isNull() && expected.isNull()
                    && ("equals".equals(operator) || "not equals".equals(operator))) {
                result = "equals".equals(operator);
                return new Evaluation(field, operator, result, result ? "true" : "false");
            }
            if (actual.isNull()) {
                throw new NodeExecutionException("Condition field '" + field + "' is null.");
            }
            if (!actual.isValueNode() || !expected.isValueNode()) {
                throw new NodeExecutionException("Condition comparisons require scalar values.");
            }
            if (isOrderingOperator(operator) && (actual.isBoolean() || expected.isBoolean())) {
                throw new NodeExecutionException("Ordering comparisons do not support boolean values.");
            }
            int comparison = compare(actual, expected);
            result = switch (operator) {
                case "equals" -> comparison == 0;
                case "not equals" -> comparison != 0;
                case "greater than" -> comparison > 0;
                case "greater than or equal" -> comparison >= 0;
                case "less than" -> comparison < 0;
                case "less than or equal" -> comparison <= 0;
                default -> throw new NodeExecutionException("Condition uses an unsupported operator.");
            };
        }
        return new Evaluation(field, operator, result, result ? "true" : "false");
    }

    public void validateConfiguration(JsonNode configuration) {
        String field = requiredText(configuration, "field", "Condition requires a non-empty field");
        if (field.split("\\.", -1).length == 0
                || java.util.Arrays.stream(field.split("\\.", -1)).anyMatch(String::isBlank)) {
            throw new NodeExecutionException("Condition requires a valid field path.");
        }
        String operator = requiredText(configuration, "operator", "Condition requires an operator");
        if (!OPERATORS.contains(operator)) {
            throw new NodeExecutionException("Condition uses an unsupported operator.");
        }
        if ("is empty".equals(operator) || "is not empty".equals(operator)) {
            return;
        }
        JsonNode expected = configuration.get("value");
        if (expected == null) {
            throw new NodeExecutionException("Condition requires a comparison value.");
        }
        if (!expected.isValueNode()) {
            throw new NodeExecutionException("Condition comparisons require scalar values.");
        }
        if (isOrderingOperator(operator) && !expected.isNumber() && !expected.isTextual()) {
            throw new NodeExecutionException("Ordering comparisons require a number or text value.");
        }
    }

    private String requiredText(JsonNode configuration, String property, String message) {
        JsonNode value = configuration == null ? null : configuration.get(property);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new NodeExecutionException(message);
        }
        return value.textValue();
    }

    private JsonNode resolveField(JsonNode input, String path) {
        JsonNode current = input;
        for (String segment : path.split("\\.", -1)) {
            if (segment.isBlank() || current == null || !current.isObject()) {
                return null;
            }
            current = current.get(segment);
        }
        return current;
    }

    private int compare(JsonNode actual, JsonNode expected) {
        if (actual.isNumber() && expected.isNumber()) {
            return actual.decimalValue().compareTo(expected.decimalValue());
        }
        if (actual.isTextual() && expected.isTextual()) {
            return actual.textValue().compareTo(expected.textValue());
        }
        if (actual.isBoolean() && expected.isBoolean()) {
            return Boolean.compare(actual.booleanValue(), expected.booleanValue());
        }
        if (actual.isNull() && expected.isNull()) {
            return 0;
        }
        throw new NodeExecutionException("Condition operands have incompatible scalar types.");
    }

    private boolean isOrderingOperator(String operator) {
        return "greater than".equals(operator)
                || "greater than or equal".equals(operator)
                || "less than".equals(operator)
                || "less than or equal".equals(operator);
    }

    public record Evaluation(String field, String operator, boolean result, String selectedBranch) {
    }
}
