package com.stepflow.demo;

import com.stepflow.definition.RetryPolicy;
import com.stepflow.definition.StepContext;
import com.stepflow.definition.WorkflowDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * A demo workflow that shows what StepFlow is for. An order reserves stock, charges a card,
 * waits out a cooling-off period and ships. If shipping fails for good, the charge is
 * refunded and the stock released, newest first.
 *
 * <p>Input controls what goes wrong, so every feature can be seen in the dashboard:
 * <pre>
 * {"orderId": "A-1", "amount": 499}                          happy path
 * {"orderId": "A-2", "amount": 499, "payment": "flaky"}      payment fails twice, retried with backoff, then succeeds
 * {"orderId": "A-3", "amount": 499, "payment": "declined"}   no retries; stock is released (rolled back)
 * {"orderId": "A-4", "amount": 499, "shipping": "fail"}      refund and release run after shipping fails
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "stepflow.demo.enabled", havingValue = "true")
public class OrderFulfilmentDemo {

    @Bean
    DemoLedger demoLedger() {
        return new DemoLedger();
    }

    @Bean
    WorkflowDefinition orderFulfilment(DemoProperties props, DemoLedger ledger) {
        FakeSystems systems = new FakeSystems(ledger, props.latency().toMillis());
        RetryPolicy paymentRetries = new RetryPolicy(props.retryDelay(), 2.0, props.retryDelay().multipliedBy(30), 0.2);

        WorkflowDefinition.Builder workflow = WorkflowDefinition.builder("order-fulfilment")
                .step("reserve-stock", ctx -> Map.of("reservation", systems.reserveStock(ctx.runId(), orderId(ctx))))
                .compensateWith(ctx -> {
                    systems.releaseStock(ctx.runId(), orderId(ctx));
                    return Map.of("released", true);
                })
                .step("charge-card", 4, paymentRetries, ctx -> Map.of("charge", systems.charge(
                        ctx.runId(), orderId(ctx), amount(ctx), text(ctx, "payment"), ctx.attempt())))
                .compensateWith(ctx -> {
                    systems.refund(ctx.runId(), orderId(ctx), amount(ctx));
                    return Map.of("refunded", true);
                });
        if (!props.coolingOff().isZero()) {
            workflow.sleep("cooling-off", props.coolingOff());
        }
        return workflow
                .step("ship", ctx -> Map.of("tracking", systems.ship(ctx.runId(), orderId(ctx), text(ctx, "shipping"))))
                .build();
    }

    private static String orderId(StepContext ctx) {
        String id = text(ctx, "orderId");
        return id == null ? "order-" + ctx.runId().toString().substring(0, 8) : id;
    }

    private static int amount(StepContext ctx) {
        Object value = ctx.input().get("amount");
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String text(StepContext ctx, String key) {
        Object value = ctx.input().get(key);
        return value == null ? null : value.toString();
    }
}
