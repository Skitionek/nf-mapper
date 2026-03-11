package com.nfmapper.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ParsedPipeline {
    private final List<NfProcess> processes;
    private final List<NfWorkflow> workflows;
    private final List<NfInclude> includes;
    private final List<String[]> connections; // each [source, dest]

    public ParsedPipeline(List<NfProcess> processes, List<NfWorkflow> workflows,
                          List<NfInclude> includes, List<String[]> connections) {
        this.processes = Collections.unmodifiableList(new ArrayList<>(processes));
        this.workflows = Collections.unmodifiableList(new ArrayList<>(workflows));
        this.includes = Collections.unmodifiableList(new ArrayList<>(includes));
        this.connections = Collections.unmodifiableList(new ArrayList<>(connections));
    }

    public List<NfProcess> getProcesses() { return processes; }
    public List<NfWorkflow> getWorkflows() { return workflows; }
    public List<NfInclude> getIncludes() { return includes; }
    public List<String[]> getConnections() { return connections; }
}
