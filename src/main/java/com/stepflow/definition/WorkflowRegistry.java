package com.stepflow.definition;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Holds every {@link WorkflowDefinition} bean in the application, looked up by type. */
@Component
public class WorkflowRegistry {

    private final Map<String, WorkflowDefinition> byType = new TreeMap<>();

    @Autowired
    public WorkflowRegistry(ObjectProvider<WorkflowDefinition> definitions) {
        this(definitions.orderedStream().toList());
    }

    WorkflowRegistry(List<WorkflowDefinition> definitions) {
        for (WorkflowDefinition definition : definitions) {
            if (byType.putIfAbsent(definition.type(), definition) != null) {
                throw new IllegalStateException("Two workflows registered with type " + definition.type());
            }
        }
    }

    public WorkflowDefinition get(String type) {
        WorkflowDefinition definition = byType.get(type);
        if (definition == null) {
            throw new UnknownWorkflowException(type);
        }
        return definition;
    }

    public boolean contains(String type) {
        return byType.containsKey(type);
    }

    public Collection<WorkflowDefinition> all() {
        return Collections.unmodifiableCollection(byType.values());
    }
}
