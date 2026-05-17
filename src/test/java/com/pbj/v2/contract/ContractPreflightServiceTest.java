package com.pbj.v2.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractPreflightServiceTest {

    @Test
    void powerTowerGeneratorAlwaysEmitsRequiredTestCount() {
        ContractPreflightService service = new ContractPreflightService();

        List<GeneratedTestCase> cases = service.generateAndValidate(
                KnownContracts.powerTower(),
                List.of("edge_boundary", "random_small"));

        assertThat(cases).hasSize(2);
        for (GeneratedTestCase generated : cases) {
            int firstToken = Integer.parseInt(generated.input().trim().split("\\s+")[0]);
            assertThat(firstToken).as("first token must be T").isBetween(1, 5);
        }
        assertThat(cases.getFirst().input()).contains("UPDATE").contains("QUERY");
    }

    @Test
    void powerTowerContractRejectsInputMissingTestCount() {
        ContractValidator validator = new ContractValidator();
        String missingT = """
                2 3
                UPDATE 1 1 1 5
                QUERY 1 1 1 2 2 2
                QUERY 1 1 1 1 1 1
                """;

        assertThatThrownBy(() -> validator.validateInput(KnownContracts.powerTower(), missingT))
                .isInstanceOf(ContractViolationException.class)
                .hasMessageContaining("m is not an integer");
    }
}
