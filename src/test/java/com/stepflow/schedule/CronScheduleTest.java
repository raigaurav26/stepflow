package com.stepflow.schedule;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CronScheduleTest {

    @Test
    void firesInTheScheduleTimeZone() {
        CronSchedule weekdayMorning = CronSchedule.parse("0 0 9 * * MON-FRI", "Asia/Kolkata");

        // Friday 6 Nov 2026, 08:00 IST -> 09:00 IST the same day (03:30 UTC)
        assertEquals(Instant.parse("2026-11-06T03:30:00Z"),
                weekdayMorning.nextAfter(Instant.parse("2026-11-06T02:30:00Z")));
        // Friday after 9 AM -> skips the weekend to Monday 9 Nov
        assertEquals(Instant.parse("2026-11-09T03:30:00Z"),
                weekdayMorning.nextAfter(Instant.parse("2026-11-06T04:00:00Z")));
    }

    @Test
    void nextIsStrictlyAfterTheGivenTime() {
        CronSchedule everyMinute = CronSchedule.parse("0 * * * * *", "UTC");
        Instant onTheMinute = Instant.parse("2026-11-06T10:15:00Z");

        assertEquals(Instant.parse("2026-11-06T10:16:00Z"), everyMinute.nextAfter(onTheMinute));
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("every day", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("0 0 9 * *", "UTC"),
                "five-field cron is missing the seconds field");
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("0 0 9 * * *", "Mars/Olympus"));
    }
}
