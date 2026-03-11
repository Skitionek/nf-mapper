package com.nfmapper.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NfWorkflow {
    private final String name; // null for unnamed entry workflow
    private final List<String> calls;

    public NfWorkflow(String name, List<String> calls) {
        this.name = name;
        this.calls = Collections.unmodifiableList(new ArrayList<>(calls));
    }

    public String getName() { return name; }
    public List<String> getCalls() { return calls; }

    @Override public String toString() { return "NfWorkflow(" + name + ")"; }
}
