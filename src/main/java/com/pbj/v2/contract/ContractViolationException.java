package com.pbj.v2.contract;

public class ContractViolationException extends RuntimeException {
    public ContractViolationException(String message) {
        super(message);
    }
}
