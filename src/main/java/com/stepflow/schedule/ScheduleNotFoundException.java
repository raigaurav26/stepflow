package com.stepflow.schedule;

import java.util.UUID;

public class ScheduleNotFoundException extends RuntimeException {

    public ScheduleNotFoundException(UUID id) {
        super("No schedule with id: " + id);
    }
}
