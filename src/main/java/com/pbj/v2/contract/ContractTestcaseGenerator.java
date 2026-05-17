package com.pbj.v2.contract;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class ContractTestcaseGenerator {
    private final ContractValidator validator;

    public ContractTestcaseGenerator(ContractValidator validator) {
        this.validator = validator;
    }

    public GeneratedTestCase generate(ProblemContract contract, String profile, int seed) {
        validator.validateContract(contract);
        Random random = new Random(seed);
        StringBuilder input = new StringBuilder();
        int cases = contract.multipleTestCases()
                ? chooseCaseCount(contract, profile, random)
                : 1;
        if (contract.multipleTestCases()) {
            input.append(cases).append('\n');
        }
        for (int tc = 0; tc < cases; tc++) {
            Map<String, Long> variables = new HashMap<>();
            for (InputSection section : contract.sections()) {
                if (section.kind() == InputSection.Kind.SCALARS) {
                    emitScalars(input, section.fields(), variables, profile, random);
                } else if (section.kind() == InputSection.Kind.COMMANDS) {
                    emitCommands(input, section, variables, profile, random);
                }
            }
        }
        String generated = input.toString();
        validator.validateInput(contract, generated);
        return new GeneratedTestCase(profile, seed, generated);
    }

    private int chooseCaseCount(ProblemContract contract, String profile, Random random) {
        long min = contract.testCaseMin().resolve(Map.of());
        long max = contract.testCaseMax().resolve(Map.of());
        long cappedMax = Math.min(max, 3);
        if (isBoundary(profile)) return (int) min;
        return (int) randomLong(random, min, Math.max(min, cappedMax));
    }

    private void emitScalars(StringBuilder input, List<ScalarField> fields,
                             Map<String, Long> variables, String profile, Random random) {
        for (int i = 0; i < fields.size(); i++) {
            ScalarField field = fields.get(i);
            long min = field.min().resolve(variables);
            long max = field.max().resolve(variables);
            long value = chooseValue(field.name(), min, max, profile, random);
            variables.put(field.name(), value);
            if (i > 0) input.append(' ');
            input.append(value);
        }
        input.append('\n');
    }

    private void emitCommands(StringBuilder input, InputSection section,
                              Map<String, Long> variables, String profile, Random random) {
        long count = variables.get(section.repeatField());
        for (int i = 0; i < count; i++) {
            CommandVariant variant = chooseVariant(section.variants(), profile, i, random);
            input.append(variant.keyword());
            for (ScalarField arg : variant.args()) {
                long min = arg.min().resolve(variables);
                long max = arg.max().resolve(variables);
                long value = chooseValue(arg.name(), min, max, profile, random);
                input.append(' ').append(value);
            }
            input.append('\n');
        }
    }

    private CommandVariant chooseVariant(List<CommandVariant> variants, String profile, int index, Random random) {
        if (variants.size() == 1) return variants.getFirst();
        if (isBoundary(profile)) return variants.get(index % variants.size());
        return variants.get(random.nextInt(variants.size()));
    }

    private long chooseValue(String name, long min, long max, String profile, Random random) {
        if (min > max) {
            throw new ContractViolationException("Invalid generated bounds for " + name);
        }
        if (isBoundary(profile)) {
            return Math.floorMod(name.hashCode(), 2) == 0 ? min : max;
        }
        return randomLong(random, min, max);
    }

    private long randomLong(Random random, long min, long max) {
        if (min == max) return min;
        long span = max - min + 1;
        if (span > 0 && span <= Integer.MAX_VALUE) {
            return min + random.nextInt((int) span);
        }
        long candidate = random.nextLong(min, max);
        return Math.min(max, Math.max(min, candidate));
    }

    private boolean isBoundary(String profile) {
        String lower = profile == null ? "" : profile.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("boundary") || lower.contains("edge");
    }
}
