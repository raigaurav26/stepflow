package com.stepflow.definition;

import java.util.Map;

/**
 * The code that runs for one step. It returns the step's output, which is stored as JSON
 * and handed to later steps. Throwing an exception counts as a failed attempt.
 *
 * <p>Handlers can run more than once (retries, a worker crashing after the work but before
 * saving the result), so they should be safe to repeat.
 */
@FunctionalInterface
public interface StepHandler {

    Map<String, Object> execute(StepContext context) throws Exception;
}
