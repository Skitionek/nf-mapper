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
    /**
     * Number of parse errors encountered while producing this pipeline (0 for a
     * successful parse, including genuinely empty-but-valid input such as a
     * comments-only file). A pipeline with no processes/workflows and a non-zero
     * error count indicates a diagnostic result, not a valid empty pipeline.
     */
    private final int parseErrorCount;

    public ParsedPipeline(List<NfProcess> processes, List<NfWorkflow> workflows,
                          List<NfInclude> includes, List<String[]> connections,
                          Map<String, String[]> conditionalInfo, int parseErrorCount) {
        this.processes = Collections.unmodifiableList(new ArrayList<>(processes));
        this.workflows = Collections.unmodifiableList(new ArrayList<>(workflows));
        this.includes = Collections.unmodifiableList(new ArrayList<>(includes));
        this.connections = Collections.unmodifiableList(new ArrayList<>(connections));
        this.conditionalInfo = Collections.unmodifiableMap(new LinkedHashMap<>(conditionalInfo));
        this.parseErrorCount = parseErrorCount;
    }

    /** Backward-compatible constructor – no conditional-info, no parse errors. */
    public ParsedPipeline(List<NfProcess> processes, List<NfWorkflow> workflows,
                          List<NfInclude> includes, List<String[]> connections,
                          Map<String, String[]> conditionalInfo) {
        this(processes, workflows, includes, connections, conditionalInfo, 0);
    }

    /** Backward-compatible constructor – no conditional-info, no parse errors. */
    public ParsedPipeline(List<NfProcess> processes, List<NfWorkflow> workflows,
                          List<NfInclude> includes, List<String[]> connections) {
        this(processes, workflows, includes, connections, Collections.emptyMap(), 0);
    }

    public List<NfProcess> getProcesses() { return processes; }
    public List<NfWorkflow> getWorkflows() { return workflows; }
    public List<NfInclude> getIncludes() { return includes; }
    public List<String[]> getConnections() { return connections; }
    public Map<String, String[]> getConditionalInfo() { return conditionalInfo; }
    public int getParseErrorCount() { return parseErrorCount; }

    /**
     * True when this pipeline has no visible nodes AND that emptiness is caused by
     * parse errors rather than genuinely valid empty input (e.g. a comments-only
     * file). Callers use this to distinguish a diagnostic result from a valid
     * no-op result.
     */
    public boolean isParseFailure() {
        return parseErrorCount > 0 && processes.isEmpty() && workflows.isEmpty();
    }
}
