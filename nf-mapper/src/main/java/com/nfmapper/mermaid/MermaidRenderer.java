package com.nfmapper.mermaid;

import com.nfmapper.model.*;
import java.util.*;
import java.util.stream.*;

public class MermaidRenderer {

    // -------------------------------------------------------------------------
    // Default configuration
    // -------------------------------------------------------------------------

    /** Mermaid theme – "base" allows full themeVariables customisation. */
    private static final String DEFAULT_THEME = "base";

    /** nf-core / metro-map brand colours (git0 = main line, git1-7 = branches). */
    private static final Map<String, Object> DEFAULT_THEME_VARS;
    static {
        DEFAULT_THEME_VARS = new LinkedHashMap<>();
        DEFAULT_THEME_VARS.put("git0", "#24B064");  // nf-core green  – main
        DEFAULT_THEME_VARS.put("gitInv0", "#ffffff");
        DEFAULT_THEME_VARS.put("git1", "#FA7F19");  // orange
        DEFAULT_THEME_VARS.put("gitInv1", "#ffffff");
        DEFAULT_THEME_VARS.put("git2", "#0570b0");  // blue
        DEFAULT_THEME_VARS.put("gitInv2", "#ffffff");
        DEFAULT_THEME_VARS.put("git3", "#e63946");  // red
        DEFAULT_THEME_VARS.put("gitInv3", "#ffffff");
        DEFAULT_THEME_VARS.put("git4", "#9b59b6");  // purple
        DEFAULT_THEME_VARS.put("gitInv4", "#ffffff");
        DEFAULT_THEME_VARS.put("git5", "#f5c542");  // yellow
        DEFAULT_THEME_VARS.put("gitInv5", "#000000");
        DEFAULT_THEME_VARS.put("git6", "#1abc9c");  // teal
        DEFAULT_THEME_VARS.put("gitInv6", "#ffffff");
        DEFAULT_THEME_VARS.put("git7", "#7b2d3b");  // dark red
        DEFAULT_THEME_VARS.put("gitInv7", "#ffffff");
    }

    /** Default gitGraph-section options. */
    private static final Map<String, Object> DEFAULT_GITGRAPH_CONFIG;
    static {
        DEFAULT_GITGRAPH_CONFIG = new LinkedHashMap<>();
        DEFAULT_GITGRAPH_CONFIG.put("showBranches", true);
        DEFAULT_GITGRAPH_CONFIG.put("parallelCommits", false);
    }

    public String render(ParsedPipeline pipeline, String title, Map<String, Object> configOverrides) {
        List<String> lines = new ArrayList<>();

        if (title != null && !title.isEmpty()) {
            lines.add("---");
            lines.add("title: " + title);
            lines.add("---");
        }

        Map<String, Object> mergedGitGraph = new LinkedHashMap<>(DEFAULT_GITGRAPH_CONFIG);
        if (configOverrides != null) mergedGitGraph.putAll(configOverrides);

        lines.add("%%{init: {'theme': '" + DEFAULT_THEME + "', 'themeVariables': "
                + formatConfig(DEFAULT_THEME_VARS) + ", 'gitGraph': "
                + formatConfig(mergedGitGraph) + "} }%%");
        lines.add("gitGraph LR:");
        lines.add("   checkout main");

        if (!pipeline.getConnections().isEmpty()) {
            renderDag(lines, pipeline);
        } else {
            renderFlat(lines, pipeline);
        }

        return String.join("\n", lines);
    }

    public String render(ParsedPipeline pipeline) {
        return render(pipeline, null, null);
    }

    // -------------------------------------------------------------------------
    // Config formatting
    // -------------------------------------------------------------------------

    private String formatConfig(Map<String, Object> config) {
        return formatConfig(config, 0);
    }

    private String formatConfig(Map<String, Object> config, int depth) {
        if (depth > 5) return "{}"; // guard against unexpected circular references
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append("'").append(entry.getKey()).append("': ");
            Object v = entry.getValue();
            if (v instanceof Boolean b) {
                sb.append(b ? "true" : "false");
            } else if (v instanceof String s) {
                sb.append("'").append(s).append("'");
            } else if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) m;
                sb.append(formatConfig(nested, depth + 1));
            } else {
                sb.append(v);
            }
        }
        sb.append("}");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Branch-name helper
    // -------------------------------------------------------------------------

    /**
     * Derive a Mermaid-safe branch name from a process/workflow name.
     * Nextflow names use uppercase letters and underscores, which are valid Mermaid
     * branch identifiers.  We fall back to a numeric suffix only when the name is
     * empty or would otherwise collide with an already-used name.
     */
    private String branchName(String processName, Set<String> usedBranchNames) {
        // Sanitise: strip characters that are not alphanumeric or underscore/hyphen
        String candidate = processName.replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (candidate.isEmpty()) candidate = "branch";
        if (usedBranchNames.add(candidate)) return candidate;
        // Collision fallback
        int i = 2;
        while (!usedBranchNames.add(candidate + "_" + i)) i++;
        return candidate + "_" + i;
    }

    // -------------------------------------------------------------------------
    // Channel helpers
    // -------------------------------------------------------------------------

    private String fileExtension(String pattern) {
        if (pattern == null) return null;
        String p = pattern.replaceAll("^['\"]|['\"]$", "");
        int dotIdx = p.lastIndexOf('.');
        if (dotIdx >= 0 && dotIdx < p.length() - 1) {
            String ext = p.substring(dotIdx + 1);
            return ext.isEmpty() ? null : ext;
        }
        return null;
    }

    private List<String[]> channelIdsWithExt(String procName, Map<String, NfProcess> procLookup) {
        NfProcess proc = procLookup.get(procName);
        if (proc == null) return Collections.emptyList();
        List<String[]> result = new ArrayList<>();
        for (String pattern : proc.getOutputs()) {
            String cid = procName + ": " + pattern;
            String ext = fileExtension(pattern);
            result.add(new String[]{cid, ext});
        }
        return result;
    }

    /**
     * Emit a single aggregated HIGHLIGHT commit for the outputs of {@code procName}.
     * Up to 2 output patterns are shown as tags (full name, e.g. {@code *.yml});
     * any further outputs are summarised as a {@code +N more} tag on the third slot.
     * All channel IDs are registered in {@code channelBranch} (when non-null) so that
     * downstream cherry-pick logic can still discover them.
     *
     * @param channelBranch branch-tracking map; may be {@code null} when branch tracking
     *                      is not required (e.g. flat rendering, which has no cherry-picks)
     * @param currentBranch the branch currently being written to; ignored when
     *                      {@code channelBranch} is {@code null}
     */
    private void emitAggregatedChannelHighlights(List<String> lines,
                                                   String procName,
                                                   Map<String, NfProcess> procLookup,
                                                   Map<String, String> channelBranch,
                                                   String currentBranch) {
        List<String[]> channels = channelIdsWithExt(procName, procLookup);
        if (channels.isEmpty()) return;
        // Register all channel IDs in the branch map so downstream processes can
        // discover which branch they were produced on (used by cherry-pick detection).
        if (channelBranch != null) {
            for (String[] cidExt : channels) channelBranch.put(cidExt[0], currentBranch);
        }
        // Build the commit: when there are multiple outputs, use "PROC: *" as the commit
        // ID to signal that it covers several patterns; for a single output, use the exact
        // channel ID so the node name precisely reflects the one output pattern.
        int n = channels.size();
        String cid = n == 1 ? channels.get(0)[0] : procName + ": *";
        StringBuilder sb = new StringBuilder("   commit id: \"").append(cid).append("\" type: HIGHLIGHT");
        int tagsToShow = Math.min(n, 2);
        for (int i = 0; i < tagsToShow; i++) {
            // Extract the raw pattern from the channel ID ("PROC: *.pattern" → "*.pattern")
            String pattern = channels.get(i)[0].substring(procName.length() + 2);
            sb.append(" tag: \"").append(pattern).append("\"");
        }
        int remaining = n - tagsToShow;
        if (remaining > 0) {
            sb.append(" tag: \"+").append(remaining).append(" more\"");
        }
        lines.add(sb.toString());
    }

    /**
     * Emit a process commit (with preceding cherry-picks and following channel HIGHLIGHT
     * commits).  If {@code processName} is a conditional call (recorded in
     * {@code conditionalInfo}), a {@code type: REVERSE} commit is emitted first as an
     * explicit "if-statement node" in the diagram.
     *
     * <p>The if-node ID is {@code "if: <processName>"} which is globally unique
     * (process names are unique) so the same node is never emitted twice.
     */
    private void emitNodeWithChannels(List<String> lines,
                                       String procName,
                                       Map<String, NfProcess> procLookup,
                                       Map<String, List<String>> predecessors,
                                       Map<String, String> channelBranch,
                                       String currentBranch,
                                       Map<String, String[]> conditionalInfo) {
        // 1. Collect predecessor channels committed on a different branch, then emit a
        //    single aggregated cherry-pick to reduce visual clutter.  The second channel
        //    ID (if any) is shown as an explicit tag; further ones are summarised as
        //    "+N more" on a second tag slot.
        List<String> cherryPickIds = new ArrayList<>();
        for (String src : predecessors.getOrDefault(procName, Collections.emptyList())) {
            for (String[] cidExt : channelIdsWithExt(src, procLookup)) {
                String cid = cidExt[0];
                if (channelBranch.containsKey(cid) && !channelBranch.get(cid).equals(currentBranch)) {
                    cherryPickIds.add(cid);
                }
            }
        }
        if (!cherryPickIds.isEmpty()) {
            StringBuilder sb = new StringBuilder("   cherry-pick id: \"").append(cherryPickIds.get(0)).append("\"");
            int extras = cherryPickIds.size() - 1;
            if (extras >= 1) {
                sb.append(" tag: \"").append(cherryPickIds.get(1)).append("\"");
            }
            if (extras > 1) {
                sb.append(" tag: \"+").append(extras - 1).append(" more\"");
            }
            lines.add(sb.toString());
        }

        // 2. If-statement node (for conditional calls) – one per process, always unique
        String[] condInfo = conditionalInfo.get(procName);
        if (condInfo != null) {
            lines.add("   commit id: \"if: " + procName + "\" type: REVERSE");
        }

        // 3. Process commit
        lines.add("   commit id: \"" + procName + "\"");

        // 4. Output channel HIGHLIGHT commits (aggregated into one when there are multiple)
        emitAggregatedChannelHighlights(lines, procName, procLookup, channelBranch, currentBranch);
    }

    // -------------------------------------------------------------------------
    // Main-block file reference helpers
    // -------------------------------------------------------------------------

    /**
     * Collect all unique file-reference patterns from the {@code main:} blocks of all
     * workflows in the pipeline, preserving declaration order.
     */
    private List<String> collectAllMainFileRefs(ParsedPipeline pipeline) {
        List<String> refs = new ArrayList<>();
        for (NfWorkflow wf : pipeline.getWorkflows()) {
            for (String ref : wf.getMainFileRefs()) {
                if (!refs.contains(ref)) refs.add(ref);
            }
        }
        return refs;
    }

    /**
     * Emit a HIGHLIGHT commit for each file-reference pattern found in workflow
     * {@code main:} blocks, placing them on the current ({@code main}) branch before
     * any process commits.
     */
    private void emitMainFileRefCommits(List<String> lines, ParsedPipeline pipeline) {
        for (String pattern : collectAllMainFileRefs(pipeline)) {
            String ext = fileExtension(pattern);
            String tagPart = ext != null ? " tag: \"" + ext + "\"" : "";
            lines.add("   commit id: \"input: " + pattern + "\" type: HIGHLIGHT" + tagPart);
        }
    }

    // -------------------------------------------------------------------------
    // Flat rendering (no connections)
    // -------------------------------------------------------------------------

    private void renderFlat(List<String> lines, ParsedPipeline pipeline) {
        List<String> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Named sub-workflows have been unfolded into processes; exclude them as
        // independent graph nodes so they don't appear as orphan commits.
        Set<String> namedWfNames = namedWorkflowNames(pipeline);

        Set<String> allProcessNames = new LinkedHashSet<>();
        for (NfProcess p : pipeline.getProcesses()) allProcessNames.add(p.getName());
        for (NfInclude inc : pipeline.getIncludes()) {
            inc.getImports().stream().filter(imp -> !namedWfNames.contains(imp))
                            .forEach(allProcessNames::add);
        }

        for (NfWorkflow wf : pipeline.getWorkflows()) {
            for (String call : wf.getCalls()) {
                if (allProcessNames.contains(call) && seen.add(call)) {
                    ordered.add(call);
                }
            }
        }
        for (NfProcess proc : pipeline.getProcesses()) {
            if (seen.add(proc.getName())) ordered.add(proc.getName());
        }

        Map<String, NfProcess> procLookup = new LinkedHashMap<>();
        for (NfProcess p : pipeline.getProcesses()) procLookup.put(p.getName(), p);

        Map<String, String[]> conditionalInfo = pipeline.getConditionalInfo();
        Set<String> usedBranchNames = new LinkedHashSet<>();

        // Build ordered segments: each segment is either a single name (non-conditional)
        // or a list of names sharing the same if-group (emitted together on one branch).
        // groupId -> ordered list of names in that group
        // Each segment is a List<String>: single-element for unconditional processes,
        // or multi-element for an if-group (all members placed on the same branch).
        List<List<String>> segments = new ArrayList<>();
        Set<String> handledGroups = new LinkedHashSet<>();

        for (String name : ordered) {
            String[] ci = conditionalInfo.get(name);
            if (ci == null) {
                segments.add(List.of(name));
            } else {
                String groupId = ci[0];
                if (handledGroups.add(groupId)) {
                    // Collect all members of this group in their call order
                    List<String> members = new ArrayList<>();
                    for (String n2 : ordered) {
                        String[] ci2 = conditionalInfo.get(n2);
                        if (ci2 != null && ci2[0].equals(groupId)) members.add(n2);
                    }
                    segments.add(members);
                }
                // Skip subsequent members; they were collected into the group above
            }
        }

        if (segments.isEmpty()) return;

        // Emit file references from workflow main blocks before any process commits
        emitMainFileRefCommits(lines, pipeline);

        // First segment always goes on main
        emitFlatSegment(lines, segments.get(0), "main", procLookup, conditionalInfo);

        // Remaining segments each get their own branch named after their first process
        for (int i = 1; i < segments.size(); i++) {
            List<String> seg = segments.get(i);
            String firstName = seg.get(0);
            String bname = branchName(firstName, usedBranchNames);
            lines.add("   branch " + bname);
            lines.add("   checkout " + bname);
            emitFlatSegment(lines, seg, bname, procLookup, conditionalInfo);
            lines.add("   checkout main");
        }
    }

    private void emitFlatSegment(List<String> lines, List<String> names, String branchName,
                                  Map<String, NfProcess> procLookup,
                                  Map<String, String[]> conditionalInfo) {
        for (String name : names) {
            // If-statement node for conditional calls – one per process, always unique
            if (conditionalInfo.containsKey(name)) {
                lines.add("   commit id: \"if: " + name + "\" type: REVERSE");
            }
            lines.add("   commit id: \"" + name + "\"");
            emitAggregatedChannelHighlights(lines, name, procLookup, null, null);
        }
    }

    // -------------------------------------------------------------------------
    // DAG-based rendering
    // -------------------------------------------------------------------------

    private void renderDag(List<String> lines, ParsedPipeline pipeline) {
        Map<String, List<String>> successors = new LinkedHashMap<>();
        Map<String, List<String>> predecessors = new LinkedHashMap<>();
        Set<String> nodes = new LinkedHashSet<>();

        // Named sub-workflows have been unfolded; exclude them so they don't become
        // orphan nodes that clutter the graph.
        Set<String> namedWfNames = namedWorkflowNames(pipeline);

        for (NfProcess p : pipeline.getProcesses()) nodes.add(p.getName());
        for (NfInclude inc : pipeline.getIncludes()) {
            inc.getImports().stream().filter(imp -> !namedWfNames.contains(imp))
                            .forEach(nodes::add);
        }
        for (String[] conn : pipeline.getConnections()) {
            nodes.add(conn[0]); nodes.add(conn[1]);
            successors.computeIfAbsent(conn[0], k -> new ArrayList<>()).add(conn[1]);
            predecessors.computeIfAbsent(conn[1], k -> new ArrayList<>()).add(conn[0]);
        }
        for (String n : nodes) {
            successors.putIfAbsent(n, new ArrayList<>());
            predecessors.putIfAbsent(n, new ArrayList<>());
        }

        if (nodes.isEmpty()) return;

        Map<String, NfProcess> procLookup = new LinkedHashMap<>();
        for (NfProcess p : pipeline.getProcesses()) procLookup.put(p.getName(), p);

        Map<String, String> channelBranch = new LinkedHashMap<>();
        Map<String, String[]> conditionalInfo = pipeline.getConditionalInfo();
        Set<String> usedBranchNames = new LinkedHashSet<>();

        List<String> topo = topoSort(nodes, predecessors, successors);

        Map<String, Integer> dist = new LinkedHashMap<>();
        Map<String, String> pathPred = new LinkedHashMap<>();
        for (String n : topo) { dist.put(n, 0); pathPred.put(n, null); }
        for (String n : topo) {
            for (String s : successors.get(n)) {
                if (dist.get(n) + 1 > dist.getOrDefault(s, 0)) {
                    dist.put(s, dist.get(n) + 1);
                    pathPred.put(s, n);
                }
            }
        }

        List<String> sinks = topo.stream().filter(n -> successors.get(n).isEmpty()).collect(Collectors.toList());
        if (sinks.isEmpty()) sinks = new ArrayList<>(topo);
        String end = sinks.stream().max(Comparator.comparingInt(dist::get)).orElse(topo.get(topo.size() - 1));

        List<String> mainPath = tracePath(end, pathPred);
        Set<String> mainSet = new LinkedHashSet<>(mainPath);

        Map<String, List<String>> branchHang = new LinkedHashMap<>();
        for (String node : topo) {
            if (mainSet.contains(node)) continue;
            String mpPred = latestMainPred(node, predecessors.get(node), mainPath);
            branchHang.computeIfAbsent(mpPred, k -> new ArrayList<>()).add(node);
        }

        if (!mainPath.isEmpty() && branchHang.containsKey(null)) {
            branchHang.computeIfAbsent(mainPath.get(0), k -> new ArrayList<>())
                       .addAll(branchHang.remove(null));
        }

        Set<String> emitted = new LinkedHashSet<>();
        final String[] currentBranch = {"main"};

        // Emit file references from workflow main blocks before the first process commit
        emitMainFileRefCommits(lines, pipeline);

        for (String node : mainPath) {
            if (!emitted.contains(node)) {
                emitNodeWithChannels(lines, node, procLookup, predecessors, channelBranch,
                                     currentBranch[0], conditionalInfo);
                emitted.add(node);
            }

            for (String offNode : branchHang.getOrDefault(node, Collections.emptyList())) {
                String bname = branchName(offNode, usedBranchNames);
                lines.add("   branch " + bname);
                lines.add("   checkout " + bname);
                currentBranch[0] = bname;

                emitOffChainWithChannels(lines, offNode, mainSet, successors, predecessors,
                                          procLookup, channelBranch, currentBranch[0],
                                          conditionalInfo);

                String mergeTarget = findMergeTarget(offNode, successors, mainSet);
                if (mergeTarget != null) {
                    int nodeIdx = mainPath.indexOf(node);
                    int mergeIdx = mainPath.indexOf(mergeTarget);
                    lines.add("   checkout main");
                    currentBranch[0] = "main";
                    for (int i = nodeIdx + 1; i < mergeIdx; i++) {
                        String step = mainPath.get(i);
                        if (!emitted.contains(step)) {
                            emitNodeWithChannels(lines, step, procLookup, predecessors,
                                                  channelBranch, currentBranch[0],
                                                  conditionalInfo);
                            emitted.add(step);
                        }
                    }
                    lines.add("   merge " + bname);
                    emitted.add(mergeTarget);
                    emitAggregatedChannelHighlights(lines, mergeTarget, procLookup,
                                                    channelBranch, currentBranch[0]);
                } else {
                    lines.add("   checkout main");
                    currentBranch[0] = "main";
                }
            }
        }
    }

    private void emitOffChainWithChannels(List<String> lines,
                                           String start,
                                           Set<String> mainSet,
                                           Map<String, List<String>> successors,
                                           Map<String, List<String>> predecessors,
                                           Map<String, NfProcess> procLookup,
                                           Map<String, String> channelBranch,
                                           String currentBranch,
                                           Map<String, String[]> conditionalInfo) {
        Set<String> visited = new LinkedHashSet<>();
        String cur = start;
        while (cur != null && visited.add(cur)) {
            emitNodeWithChannels(lines, cur, procLookup, predecessors, channelBranch,
                                  currentBranch, conditionalInfo);
            List<String> offSuccs = successors.getOrDefault(cur, Collections.emptyList()).stream()
                    .filter(s -> !mainSet.contains(s)).collect(Collectors.toList());
            cur = offSuccs.isEmpty() ? null : offSuccs.get(0);
        }
    }

    private String findMergeTarget(String offStart, Map<String, List<String>> successors, Set<String> mainSet) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(offStart);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            if (visited.contains(node)) continue;
            visited.add(node);
            for (String s : successors.getOrDefault(node, Collections.emptyList())) {
                if (mainSet.contains(s)) return s;
                queue.add(s);
            }
        }
        return null;
    }

    private List<String> topoSort(Set<String> nodes, Map<String, List<String>> predecessors,
                                   Map<String, List<String>> successors) {
        Map<String, Integer> inDeg = new LinkedHashMap<>();
        for (String n : nodes) inDeg.put(n, predecessors.getOrDefault(n, Collections.emptyList()).size());

        Deque<String> queue = new ArrayDeque<>(nodes.stream()
                .filter(n -> inDeg.get(n) == 0)
                .sorted()
                .collect(Collectors.toList()));

        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String n = queue.poll();
            result.add(n);
            successors.getOrDefault(n, Collections.emptyList()).stream().sorted().forEach(s -> {
                inDeg.merge(s, -1, Integer::sum);
                if (inDeg.get(s) == 0) queue.add(s);
            });
        }
        nodes.stream().sorted().filter(n -> !result.contains(n)).forEach(result::add);
        return result;
    }

    private List<String> tracePath(String end, Map<String, String> pred) {
        List<String> path = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        String cur = end;
        // Guard against cycles in pathPred (which can occur when the connection graph
        // contains cycles that survived into the dist-computation pass).
        while (cur != null && visited.add(cur)) {
            path.add(cur);
            cur = pred.get(cur);
        }
        Collections.reverse(path);
        return path;
    }

    private String latestMainPred(String node, List<String> nodePreds, List<String> mainPath) {
        List<String> candidates = nodePreds.stream()
                .filter(mainPath::contains).collect(Collectors.toList());
        if (candidates.isEmpty()) return null;
        return candidates.stream().max(Comparator.comparingInt(mainPath::indexOf)).orElse(null);
    }

    /**
     * Returns the set of named (non-entry) workflow names in the pipeline.
     * Used to exclude sub-workflows that have been unfolded from the graph node sets.
     */
    private Set<String> namedWorkflowNames(ParsedPipeline pipeline) {
        Set<String> names = new LinkedHashSet<>();
        for (NfWorkflow wf : pipeline.getWorkflows()) {
            if (wf.getName() != null) names.add(wf.getName());
        }
        return names;
    }
}
