package com.stepflow.run;

import java.util.UUID;

public class RunNotFoundException extends RuntimeException {

    public RunNotFoundException(UUID id) {
        super("No workflow run with id: " + id);
    }
}
