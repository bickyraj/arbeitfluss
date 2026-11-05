package com.flowforge.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionEvaluatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    @Test
    void evaluatesStringBooleanAndNumericComparisonsWithoutCoercion() throws Exception {
        assertResult("""
                {"field":"user.name","operator":"equals","value":"Alex"}
                """, """
                {"user":{"name":"Alex"}}
                """, true);
        assertResult("""
                {"field":"enabled","operator":"not equals","value":false}
                """, """
                {"enabled":true}
                """, true);
        assertResult("""
                {"field":"age","operator":"greater than","value":9}
                """, """
                {"age":10}
                """, true);
        assertResult("""
                {"field":"age","operator":"greater than or equal","value":18}
                """, """
                {"age":18}
                """, true);
        assertResult("""
                {"field":"age","operator":"less than","value":18}
                """, """
                {"age":17}
                """, true);
        assertResult("""
                {"field":"age","operator":"less than or equal","value":18}
                """, """
                {"age":18}
                """, true);
        assertResult("""
                {"field":"label","operator":"greater than","value":"m"}
                """, """
                {"label":"z"}
                """, true);
    }

    @Test
    void handlesEmptyMissingNullAndExplicitNullComparison() throws Exception {
        assertResult("""
                {"field":"missing","operator":"is empty"}
                """, "{}", true);
        assertResult("""
                {"field":"value","operator":"is empty"}
                """, """
                {"value":null}
                """, true);
        assertResult("""
                {"field":"value","operator":"is empty"}
                """, """
                {"value":""}
                """, true);
        assertResult("""
                {"field":"value","operator":"is not empty"}
                """, """
                {"value":false}
                """, true);
        assertResult("""
                {"field":"value","operator":"equals","value":null}
                """, """
                {"value":null}
                """, true);
        assertThatThrownBy(() -> evaluate("""
                {"field":"value","operator":"equals","value":"x"}
                """, "{}"))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("is missing");
    }

    @Test
    void rejectsUnsupportedOperatorsMissingValuesAndIncompatibleTypes() {
        assertThatThrownBy(() -> evaluate(
                "{\"field\":\"age\",\"operator\":\"contains\",\"value\":1}", "{\"age\":1}"))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("unsupported operator");
        assertThatThrownBy(() -> evaluate(
                "{\"field\":\"age\",\"operator\":\"equals\"}", "{\"age\":1}"))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("comparison value");
        assertThatThrownBy(() -> evaluate(
                "{\"field\":\"age\",\"operator\":\"greater than\",\"value\":\"9\"}", "{\"age\":10}"))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("incompatible scalar types");
        assertThatThrownBy(() -> evaluate(
                "{\"field\":\"age\",\"operator\":\"greater than\",\"value\":9}", "{\"age\":null}"))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("is null");
        assertThatThrownBy(() -> evaluate(
                "{\"field\":\"enabled\",\"operator\":\"greater than\",\"value\":false}", "{\"enabled\":true}"))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("number or text");
        assertThatThrownBy(() -> evaluate(
                "{\"field\":\"values\",\"operator\":\"is empty\"}", "{\"values\":[]}"))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("scalar");
    }

    private void assertResult(String configuration, String input, boolean expected) throws Exception {
        ConditionEvaluator.Evaluation evaluation = evaluator.evaluate(
                objectMapper.readTree(configuration),
                objectMapper.readTree(input)
        );
        assertThat(evaluation.result()).isEqualTo(expected);
        assertThat(evaluation.selectedBranch()).isEqualTo(expected ? "true" : "false");
    }

    private ConditionEvaluator.Evaluation evaluate(String configuration, String input) {
        JsonNode config;
        JsonNode data;
        try {
            config = objectMapper.readTree(configuration);
            data = objectMapper.readTree(input);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return evaluator.evaluate(config, data);
    }
}
