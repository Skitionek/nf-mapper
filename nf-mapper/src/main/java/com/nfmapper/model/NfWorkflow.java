package com.nfmapper.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NfWorkflow {
    private final String name; // null for unnamed entry workflow
    private final List<String> calls;
    /** File patterns found in the {@code main:} block (e.g. from Channel.fromPath, file()). */
    private final List<String> mainFileRefs;

    public NfWorkflow(String name, List<String> calls) {
        this(name, calls, Collections.emptyList());
    }

    public NfWorkflow(String name, List<String> calls, List<String> mainFileRefs) {
        this.name = name;
        this.calls = Collections.unmodifiableList(new ArrayList<>(calls));
        this.mainFileRefs = Collections.unmodifiableList(new ArrayList<>(mainFileRefs));
    }

    public String getName() { return name; }
    public List<String> getCalls() { return calls; }
    public List<String> getMainFileRefs() { return mainFileRefs; }

    @Override public String toString() { return "NfWorkflow(" + name + ")"; }
}
