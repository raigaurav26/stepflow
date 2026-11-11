package com.stepflow.demo;

import java.util.ArrayList;
import java.util.List;

/** What the pretend external systems did, in order, so the demo (and its tests) can see it. */
public class DemoLedger {

    private static final int KEEP = 500;

    private final List<String> entries = new ArrayList<>();

    public synchronized void add(String entry) {
        entries.add(entry);
        if (entries.size() > KEEP) {
            entries.remove(0);
        }
    }

    public synchronized List<String> entries() {
        return List.copyOf(entries);
    }

    public synchronized void clear() {
        entries.clear();
    }
}
