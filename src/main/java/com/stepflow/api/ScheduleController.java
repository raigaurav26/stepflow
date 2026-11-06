package com.stepflow.api;

import com.stepflow.schedule.Schedule;
import com.stepflow.schedule.ScheduleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @PostMapping
    public ResponseEntity<Schedule> create(@Valid @RequestBody CreateScheduleRequest request) {
        Schedule schedule = scheduleService.create(request.name(), request.workflowType(), request.cron(),
                request.zone(), request.input());
        return ResponseEntity.status(HttpStatus.CREATED).body(schedule);
    }

    @GetMapping
    public List<Schedule> list() {
        return scheduleService.list();
    }

    @GetMapping("/{id}")
    public Schedule get(@PathVariable UUID id) {
        return scheduleService.get(id);
    }

    @PostMapping("/{id}/pause")
    public Schedule pause(@PathVariable UUID id) {
        return scheduleService.setEnabled(id, false);
    }

    @PostMapping("/{id}/resume")
    public Schedule resume(@PathVariable UUID id) {
        return scheduleService.setEnabled(id, true);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        scheduleService.delete(id);
    }
}
