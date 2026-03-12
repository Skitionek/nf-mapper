package com.nfmapper.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ParsedPipeline {
    private final List<NfProcess> processes;
    private final List<NfWorkflow> workflows;
    private final List<NfInclude> includes;
    private final List<String[]> connections; // each [source, dest]
    /**
     * Maps a process/workflow call name to a two-element array:
     * <ul>
     *   <li>[0] – integer group-id (as String) shared by all calls in the same {@code if} block</li>
     *   <li>[1] – human-readable condition text extracted from the {@code if} expression</li>
     * </ul>
     * Only contains entries for calls that appear inside an {@code if} or {@code else} block.
     */
    private final Map<String, String[]> conditionalInfo;

    public ParsedPipeline(List<NfProcess> processes, List<NfWorkflow> workflows,
                          List<NfInclude> includes, List<String[]> connections,
                          Map<String, String[]> conditionalInfo) {
        this.processes = Collections.unmodifiableList(new ArrayList<>(processes));
        this.workflows = Collections.unmodifiableList(new ArrayList<>(workflows));
        this.includes = Collections.unmodifiableList(new ArrayList<>(includes));
        this.connections = Collections.unmodifiableList(new ArrayList<>(connections));
        this.conditionalInfo = Collections.unmodifiableMap(new LinkedHashMap<>(conditionalInfo));
    }

    /** Backward-compatible constructor – no conditional-info. */
    public ParsedPipeline(List<NfProcess> processes, List<NfWorkflow> workflows,
                          List<NfInclude> includes, List<String[]> connections) {
        this(processes, workflows, includes, connections, Collections.emptyMap());
    }

    public List<NfProcess> getProcesses() { return processes; }
    public List<NfWorkflow> getWorkflows() { return workflows; }
    public List<NfInclude> getIncludes() { return includes; }
    public List<String[]> getConnections() { return connections; }
    public Map<String, String[]> getConditionalInfo() { return conditionalInfo; }
}
