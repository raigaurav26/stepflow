package com.stepflow.definition;

public class UnknownWorkflowException extends RuntimeException {

    public UnknownWorkflowException(String type) {
        super("No workflow registered with type: " + type);
    }
}
