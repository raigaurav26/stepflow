package com.stepflow.schedule;

public class ScheduleNameTakenException extends RuntimeException {

    public ScheduleNameTakenException(String name) {
        super("A schedule named '" + name + "' already exists");
    }
}
