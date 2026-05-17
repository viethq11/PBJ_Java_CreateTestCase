package com.pbj.service;

import com.pbj.dto.SemanticSpecDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticSpecValidationServiceTest {
    private final SemanticSpecValidationService service = new SemanticSpecValidationService();

    @Test
    void acceptsFrozenConnectopolisRelations() {
        service.validate(connectopolisSpec());
    }

    @Test
    void rejectsIgnoredVariableReusedAsPathEndpoint() {
        SemanticSpecDTO spec = connectopolisSpec();
        spec.setPaths(List.of(List.of("u", "x"), List.of("y", "z")));

        assertThatThrownBy(() -> service.validate(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ignored query variable");
    }

    @Test
    void rejectsConditionPathNotDeclaredInFrozenPaths() {
        SemanticSpecDTO spec = connectopolisSpec();
        spec.setConditions(List.of("i in path(u,x)"));

        assertThatThrownBy(() -> service.validate(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not frozen");
    }

    @Test
    void acceptsNonRelationalDecisionProblemWithoutConditions() {
        SemanticSpecDTO spec = new SemanticSpecDTO();
        spec.setQueryVariables(List.of());
        spec.setIgnoredVariables(List.of());
        spec.setPaths(List.of());
        spec.setConditions(List.of());
        spec.setGraphType("multi_test");
        spec.setCountedObjects(List.of());
        spec.setOutputSemantics("winner name per test case");

        service.validate(spec);
    }

    @Test
    void acceptsCommandQueryVariablesWithoutRelationalConditions() {
        SemanticSpecDTO spec = new SemanticSpecDTO();
        spec.setQueryVariables(List.of("x1", "y1", "z1", "x2", "y2", "z2"));
        spec.setIgnoredVariables(List.of());
        spec.setPaths(List.of());
        spec.setConditions(List.of());
        spec.setGraphType("multi_test_command_based");
        spec.setCountedObjects(List.of());
        spec.setOutputSemantics("sum for each query");

        service.validate(spec);
    }

    @Test
    void rejectsRelationalSpecWithoutConditions() {
        SemanticSpecDTO spec = connectopolisSpec();
        spec.setConditions(List.of());

        assertThatThrownBy(() -> service.validate(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("relational structure");
    }

    private SemanticSpecDTO connectopolisSpec() {
        SemanticSpecDTO spec = new SemanticSpecDTO();
        spec.setQueryVariables(List.of("u", "w", "x", "y", "z"));
        spec.setIgnoredVariables(List.of("u"));
        spec.setPaths(List.of(List.of("w", "x"), List.of("y", "z")));
        spec.setConditions(List.of(
                "i in path(w,x)",
                "j in path(y,z)",
                "c[i] == c[j]",
                "i != j"
        ));
        spec.setGraphType("tree");
        return spec;
    }
}
