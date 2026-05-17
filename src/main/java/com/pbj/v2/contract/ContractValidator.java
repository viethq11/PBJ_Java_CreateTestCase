package com.pbj.v2.contract;

import java.util.HashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public class ContractValidator {

    public void validateContract(ProblemContract contract) {
        if (contract == null) {
            throw new ContractViolationException("Problem contract is missing.");
        }
        if (contract.sections().isEmpty()) {
            throw new ContractViolationException("Problem contract has no input sections.");
        }
        Set<String> variables = new HashSet<>();
        for (InputSection section : contract.sections()) {
            if (section.kind() == InputSection.Kind.SCALARS) {
                if (section.fields().isEmpty()) {
                    throw new ContractViolationException("Scalar section has no fields.");
                }
                for (ScalarField field : section.fields()) {
                    variables.add(field.name());
                }
            } else if (section.kind() == InputSection.Kind.ARRAY || section.kind() == InputSection.Kind.ROWS) {
                if (section.repeatField() == null || !variables.contains(section.repeatField())) {
                    throw new ContractViolationException(section.kind() + " section repeat field is not declared: " + section.repeatField());
                }
                if (section.fields().isEmpty()) {
                    throw new ContractViolationException(section.kind() + " section has no fields.");
                }
            } else if (section.kind() == InputSection.Kind.COMMANDS) {
                if (section.repeatField() == null || !variables.contains(section.repeatField())) {
                    throw new ContractViolationException("Command section repeat field is not declared: " + section.repeatField());
                }
                if (section.variants().isEmpty()) {
                    throw new ContractViolationException("Command section has no command variants.");
                }
                Set<String> keywords = new HashSet<>();
                for (CommandVariant variant : section.variants()) {
                    if (!keywords.add(variant.keyword())) {
                        throw new ContractViolationException("Duplicate command keyword: " + variant.keyword());
                    }
                }
            }
        }
    }

    public void validateInput(ProblemContract contract, String input) {
        validateContract(contract);
        Scanner scanner = new Scanner(input == null ? "" : input);
        int cases = 1;
        if (contract.multipleTestCases()) {
            cases = nextInt(scanner, "T");
            assertRange("T", cases, contract.testCaseMin().resolve(Map.of()), contract.testCaseMax().resolve(Map.of()));
        }
        for (int tc = 0; tc < cases; tc++) {
            java.util.Map<String, Long> variables = new java.util.HashMap<>();
            for (InputSection section : contract.sections()) {
                if (section.kind() == InputSection.Kind.SCALARS) {
                    for (ScalarField field : section.fields()) {
                        long value = nextLong(scanner, field.name());
                        long min = field.min().resolve(variables);
                        long max = field.max().resolve(variables);
                        assertRange(field.name(), value, min, max);
                        variables.put(field.name(), value);
                    }
                } else if (section.kind() == InputSection.Kind.ARRAY || section.kind() == InputSection.Kind.ROWS) {
                    long repeat = variables.getOrDefault(section.repeatField(), -1L);
                    if (repeat < 0) {
                        throw new ContractViolationException("Invalid repeat count for " + section.repeatField());
                    }
                    for (long i = 0; i < repeat; i++) {
                        for (ScalarField field : section.fields()) {
                            long value = nextLong(scanner, field.name());
                            long min = field.min().resolve(variables);
                            long max = field.max().resolve(variables);
                            assertRange(field.name(), value, min, max);
                        }
                    }
                } else if (section.kind() == InputSection.Kind.COMMANDS) {
                    long repeat = variables.getOrDefault(section.repeatField(), -1L);
                    if (repeat < 0) {
                        throw new ContractViolationException("Invalid repeat count for " + section.repeatField());
                    }
                    for (long i = 0; i < repeat; i++) {
                        String keyword = nextToken(scanner, "command keyword");
                        CommandVariant variant = section.variants().stream()
                                .filter(candidate -> candidate.keyword().equals(keyword))
                                .findFirst()
                                .orElseThrow(() -> new ContractViolationException("Unknown command keyword: " + keyword));
                        for (ScalarField arg : variant.args()) {
                            long value = nextLong(scanner, keyword + "." + arg.name());
                            long min = arg.min().resolve(variables);
                            long max = arg.max().resolve(variables);
                            assertRange(keyword + "." + arg.name(), value, min, max);
                        }
                    }
                }
            }
        }
        if (scanner.hasNext()) {
            throw new ContractViolationException("Unexpected extra token: " + scanner.next());
        }
    }

    private int nextInt(Scanner scanner, String name) {
        long value = nextLong(scanner, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new ContractViolationException(name + " is outside integer range.");
        }
        return (int) value;
    }

    private long nextLong(Scanner scanner, String name) {
        String token = nextToken(scanner, name);
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException e) {
            throw new ContractViolationException(name + " is not an integer: " + token);
        }
    }

    private String nextToken(Scanner scanner, String name) {
        if (!scanner.hasNext()) {
            throw new ContractViolationException("Missing token for " + name);
        }
        return scanner.next();
    }

    private void assertRange(String name, long value, long min, long max) {
        if (min > max) {
            throw new ContractViolationException("Invalid bounds for " + name + ": " + min + " > " + max);
        }
        if (value < min || value > max) {
            throw new ContractViolationException(name + " out of bounds: " + value + " not in [" + min + ", " + max + "]");
        }
    }
}
