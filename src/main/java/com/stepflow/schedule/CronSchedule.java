package com.stepflow.schedule;

import org.springframework.scheduling.support.CronExpression;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** A cron expression in a time zone, so "9 AM every weekday" means 9 AM in Mumbai, not UTC. */
public final class CronSchedule {

    private final CronExpression expression;
    private final ZoneId zone;

    private CronSchedule(CronExpression expression, ZoneId zone) {
        this.expression = expression;
        this.zone = zone;
    }

    /**
     * @param cron six fields: second minute hour day-of-month month day-of-week,
     *             for example {@code 0 0 9 * * MON-FRI}
     */
    public static CronSchedule parse(String cron, String zone) {
        try {
            return new CronSchedule(CronExpression.parse(cron), ZoneId.of(zone));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cron expression '" + cron + "': " + e.getMessage(), e);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Unknown time zone: " + zone, e);
        }
    }

    /** The first fire time strictly after {@code after}. */
    public Instant nextAfter(Instant after) {
        ZonedDateTime next = expression.next(after.atZone(zone));
        if (next == null) {
            throw new IllegalArgumentException("Cron expression never fires again");
        }
        return next.toInstant();
    }
}
