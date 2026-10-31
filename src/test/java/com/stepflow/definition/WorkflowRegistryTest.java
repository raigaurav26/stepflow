package com.stepflow.definition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowRegistryTest {

    private static WorkflowDefinition workflow(String type) {
        return WorkflowDefinition.builder(type).step("only", ctx -> Map.of()).build();
    }

    @Test
    void findsWorkflowByType() {
        WorkflowRegistry registry = new WorkflowRegistry(List.of(workflow("b"), workflow("a")));

        assertEquals("a", registry.get("a").type());
        assertEquals(List.of("a", "b"), registry.all().stream().map(WorkflowDefinition::type).toList());
    }

    @Test
    void unknownTypeThrows() {
        WorkflowRegistry registry = new WorkflowRegistry(List.of());
        assertThrows(UnknownWorkflowException.class, () -> registry.get("missing"));
    }

    @Test
    void duplicateTypesFailAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new WorkflowRegistry(List.of(workflow("same"), workflow("same"))));
    }
}
