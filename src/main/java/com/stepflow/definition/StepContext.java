package com.stepflow.definition;

import java.util.Map;
import java.util.UUID;

/**
 * What a step handler can see while it runs.
 *
 * @param runId           the run this step belongs to
 * @param input           the input the run was started with
 * @param previousOutputs outputs of the steps that already succeeded, keyed by step name
 * @param attempt         1 for the first try, 2 for the first retry, and so on
 */
public record StepContext(UUID runId,
                          Map<String, Object> input,
                          Map<String, Map<String, Object>> previousOutputs,
                          int attempt) {

    public Map<String, Object> outputOf(String stepName) {
        Map<String, Object> output = previousOutputs.get(stepName);
        if (output == null) {
            throw new IllegalStateException("Step '" + stepName + "' has no output yet");
        }
        return output;
    }
}
