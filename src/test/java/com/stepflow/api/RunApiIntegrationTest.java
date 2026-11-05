package com.stepflow.api;

import com.jayway.jsonpath.JsonPath;
import com.stepflow.definition.WorkflowDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RunApiIntegrationTest {

    @TestConfiguration
    static class Workflows {

        @Bean
        WorkflowDefinition orderFulfilment() {
            return WorkflowDefinition.builder("order-fulfilment")
                    .step("reserve-stock", ctx -> Map.of("reserved", true))
                    .step("charge-card", 5, ctx -> Map.of("charged", true))
                    .step("ship", ctx -> Map.of())
                    .build();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void listsRegisteredWorkflows() throws Exception {
        mvc.perform(get("/api/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'order-fulfilment')].steps[1].name").value(hasItem("charge-card")))
                .andExpect(jsonPath("$[?(@.type == 'order-fulfilment')].steps[1].maxAttempts").value(hasItem(5)));
    }

    @Test
    void startingARunStoresItAndQueuesOnlyTheFirstStep() throws Exception {
        MvcResult result = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workflowType": "order-fulfilment", "input": {"orderId": "A-100", "amount": 499}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("http://localhost/api/runs/")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.input.orderId").value("A-100"))
                .andExpect(jsonPath("$.steps.length()").value(1))
                .andExpect(jsonPath("$.steps[0].stepName").value("reserve-stock"))
                .andExpect(jsonPath("$.steps[0].status").value("PENDING"))
                .andExpect(jsonPath("$.steps[0].maxAttempts").value(3))
                .andReturn();

        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/runs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowType").value("order-fulfilment"))
                .andExpect(jsonPath("$.input.amount").value(499));

        mvc.perform(get("/api/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem(id)));
    }

    @Test
    void unknownWorkflowIsRejected() throws Exception {
        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowType\": \"does-not-exist\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("No workflow registered with type: does-not-exist"));
    }

    @Test
    void missingWorkflowTypeIsABadRequest() throws Exception {
        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": {}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownRunIs404() throws Exception {
        mvc.perform(get("/api/runs/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    private static String startBody(String orderId) {
        return "{\"workflowType\": \"order-fulfilment\", \"input\": {\"orderId\": \"" + orderId + "\", \"amount\": 499}}";
    }

    @Test
    void repeatingARequestWithTheSameKeyReturnsTheOriginalRun() throws Exception {
        String key = "checkout-" + UUID.randomUUID();
        MvcResult first = mvc.perform(post("/api/runs")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("A-200")))
                .andExpect(status().isCreated())
                .andReturn();
        String id = JsonPath.read(first.getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/runs")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("A-200")))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("Location", "http://localhost/api/runs/" + id))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.steps.length()").value(1));
    }

    @Test
    void reusingAKeyForADifferentRequestIsAConflict() throws Exception {
        String key = "checkout-" + UUID.randomUUID();
        MvcResult first = mvc.perform(post("/api/runs")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("A-300")))
                .andExpect(status().isCreated())
                .andReturn();
        String id = JsonPath.read(first.getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/runs")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("A-301")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingRunId").value(id));
    }

    @Test
    void requestsWithoutAKeyAlwaysStartNewRuns() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(startBody("A-400")))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void overlongKeyIsABadRequest() throws Exception {
        mvc.perform(post("/api/runs")
                        .header("Idempotency-Key", "k".repeat(201))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("A-500")))
                .andExpect(status().isBadRequest());
    }
}
