package com.nfmapper.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NfProcess {
    private final String name;
    private final List<String> containers;
    private final List<String> condas;
    private final List<String> templates;
    private final List<String> inputs;
    private final List<String> outputs;

    public NfProcess(String name, List<String> containers, List<String> condas,
                     List<String> templates, List<String> inputs, List<String> outputs) {
        this.name = name;
        this.containers = Collections.unmodifiableList(new ArrayList<>(containers));
        this.condas = Collections.unmodifiableList(new ArrayList<>(condas));
        this.templates = Collections.unmodifiableList(new ArrayList<>(templates));
        this.inputs = Collections.unmodifiableList(new ArrayList<>(inputs));
        this.outputs = Collections.unmodifiableList(new ArrayList<>(outputs));
    }

    // Convenience constructor for name-only (tests)
    public NfProcess(String name) {
        this(name, Collections.emptyList(), Collections.emptyList(),
             Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    public String getName() { return name; }
    public List<String> getContainers() { return containers; }
    public List<String> getCondas() { return condas; }
    public List<String> getTemplates() { return templates; }
    public List<String> getInputs() { return inputs; }
    public List<String> getOutputs() { return outputs; }

    @Override public String toString() { return "NfProcess(" + name + ")"; }
}
