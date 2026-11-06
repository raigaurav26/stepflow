package com.stepflow.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * @param cron six-field cron: second minute hour day-of-month month day-of-week
 * @param zone IANA time zone such as "Asia/Kolkata"; defaults to UTC
 */
public record CreateScheduleRequest(@NotBlank @Size(max = 100) String name,
                                    @NotBlank String workflowType,
                                    @NotBlank String cron,
                                    String zone,
                                    Map<String, Object> input) {
}
