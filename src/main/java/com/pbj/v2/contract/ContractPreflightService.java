package com.pbj.v2.contract;

import java.util.ArrayList;
import java.util.List;

public class ContractPreflightService {
    private final ContractValidator validator;
    private final ContractTestcaseGenerator generator;

    public ContractPreflightService() {
        this.validator = new ContractValidator();
        this.generator = new ContractTestcaseGenerator(validator);
    }

    public List<GeneratedTestCase> generateAndValidate(ProblemContract contract, List<String> profiles) {
        validator.validateContract(contract);
        List<GeneratedTestCase> cases = new ArrayList<>();
        List<String> effectiveProfiles = profiles == null || profiles.isEmpty()
                ? List.of("edge_boundary", "random_small")
                : profiles;
        int seed = 1;
        for (String profile : effectiveProfiles) {
            GeneratedTestCase generated = generator.generate(contract, profile, seed++);
            validator.validateInput(contract, generated.input());
            cases.add(generated);
        }
        return cases;
    }
}
