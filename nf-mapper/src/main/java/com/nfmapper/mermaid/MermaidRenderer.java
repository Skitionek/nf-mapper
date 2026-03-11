package com.nfmapper.mermaid;

import com.nfmapper.model.*;
import java.util.*;
import java.util.stream.*;

public class MermaidRenderer {

    private static final Map<String, Object> DEFAULT_CONFIG;
    static {
        DEFAULT_CONFIG = new LinkedHashMap<>();
        DEFAULT_CONFIG.put("showBranches", false);
        DEFAULT_CONFIG.put("parallelCommits", true);
    }

    public String render(ParsedPipeline pipeline, String title, Map<String, Object> configOverrides) {
        List<String> lines = new ArrayList<>();

        if (title != null && !title.isEmpty()) {
            lines.add("---");
            lines.add("title: " + title);
            lines.add("---");
        }

        Map<String, Object> mergedConfig = new LinkedHashMap<>(DEFAULT_CONFIG);
        if (configOverrides != null) mergedConfig.putAll(configOverrides);

        lines.add("%%{init: {'gitGraph': " + formatConfig(mergedConfig) + "} }%%");
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
            } else {
                sb.append(v);
            }
        }
        sb.append("}");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Channel helpers
    // -------------------------------------------------------------------------

    private String fileExtension(String pattern) {
        if (pattern == null) return null;
        // Strip surrounding quotes (defensive)
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
            result.add(new String[]{cid, ext}); // ext may be null
        }
        return result;
    }

    private void emitNodeWithChannels(List<String> lines, String procName,
                                       Map<String, NfProcess> procLookup,
                                       Map<String, List<String>> predecessors,
                                       Map<String, String> channelBranch,
                                       String currentBranch) {
        // 1. Cherry-pick predecessor channels committed on different branch
        for (String src : predecessors.getOrDefault(procName, Collections.emptyList())) {
            for (String[] cidExt : channelIdsWithExt(src, procLookup)) {
                String cid = cidExt[0];
                if (channelBranch.containsKey(cid) && !channelBranch.get(cid).equals(currentBranch)) {
                    lines.add("   cherry-pick id: \"" + cid + "\"");
                }
            }
        }

        // 2. Process commit
        lines.add("   commit id: \"" + procName + "\"");

        // 3. Output channel HIGHLIGHT commits
        for (String[] cidExt : channelIdsWithExt(procName, procLookup)) {
            String cid = cidExt[0];
            String ext = cidExt[1];
            String tagPart = ext != null ? " tag: \"" + ext + "\"" : "";
            lines.add("   commit id: \"" + cid + "\" type: HIGHLIGHT" + tagPart);
            channelBranch.put(cid, currentBranch);
        }
    }

    // -------------------------------------------------------------------------
    // Flat rendering (no connections)
    // -------------------------------------------------------------------------

    private void renderFlat(List<String> lines, ParsedPipeline pipeline) {
        List<String> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        Set<String> allProcessNames = new LinkedHashSet<>();
        for (NfProcess p : pipeline.getProcesses()) allProcessNames.add(p.getName());
        for (NfInclude inc : pipeline.getIncludes()) allProcessNames.addAll(inc.getImports());

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

        if (ordered.size() <= 1) {
            for (String name : ordered) {
                lines.add("   commit id: \"" + name + "\"");
                for (String[] cidExt : channelIdsWithExt(name, procLookup)) {
                    String cid = cidExt[0]; String ext = cidExt[1];
                    String tagPart = ext != null ? " tag: \"" + ext + "\"" : "";
                    lines.add("   commit id: \"" + cid + "\" type: HIGHLIGHT" + tagPart);
                }
            }
        } else {
            String first = ordered.get(0);
            lines.add("   commit id: \"" + first + "\"");
            for (String[] cidExt : channelIdsWithExt(first, procLookup)) {
                String cid = cidExt[0]; String ext = cidExt[1];
                String tagPart = ext != null ? " tag: \"" + ext + "\"" : "";
                lines.add("   commit id: \"" + cid + "\" type: HIGHLIGHT" + tagPart);
            }
            int branchCounter = 0;
            for (int i = 1; i < ordered.size(); i++) {
                String name = ordered.get(i);
                branchCounter++;
                String bname = "branch_" + branchCounter;
                lines.add("   branch " + bname);
                lines.add("   checkout " + bname);
                lines.add("   commit id: \"" + name + "\"");
                for (String[] cidExt : channelIdsWithExt(name, procLookup)) {
                    String cid = cidExt[0]; String ext = cidExt[1];
                    String tagPart = ext != null ? " tag: \"" + ext + "\"" : "";
                    lines.add("   commit id: \"" + cid + "\" type: HIGHLIGHT" + tagPart);
                }
                lines.add("   checkout main");
            }
        }
    }

    // -------------------------------------------------------------------------
    // DAG-based rendering
    // -------------------------------------------------------------------------

    private void renderDag(List<String> lines, ParsedPipeline pipeline) {
        // Build adjacency
        Map<String, List<String>> successors = new LinkedHashMap<>();
        Map<String, List<String>> predecessors = new LinkedHashMap<>();
        Set<String> nodes = new LinkedHashSet<>();

        for (NfProcess p : pipeline.getProcesses()) nodes.add(p.getName());
        for (NfInclude inc : pipeline.getIncludes()) nodes.addAll(inc.getImports());
        for (String[] conn : pipeline.getConnections()) {
            nodes.add(conn[0]); nodes.add(conn[1]);
            successors.computeIfAbsent(conn[0], k -> new ArrayList<>()).add(conn[1]);
            predecessors.computeIfAbsent(conn[1], k -> new ArrayList<>()).add(conn[0]);
        }
        // Initialize empty lists for all nodes
        for (String n : nodes) {
            successors.putIfAbsent(n, new ArrayList<>());
            predecessors.putIfAbsent(n, new ArrayList<>());
        }

        if (nodes.isEmpty()) return;

        Map<String, NfProcess> procLookup = new LinkedHashMap<>();
        for (NfProcess p : pipeline.getProcesses()) procLookup.put(p.getName(), p);

        Map<String, String> channelBranch = new LinkedHashMap<>();

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

        // Find sink with max distance
        List<String> sinks = topo.stream().filter(n -> successors.get(n).isEmpty()).collect(Collectors.toList());
        if (sinks.isEmpty()) sinks = new ArrayList<>(topo);
        String end = sinks.stream().max(Comparator.comparingInt(dist::get)).orElse(topo.get(topo.size() - 1));

        List<String> mainPath = tracePath(end, pathPred);
        Set<String> mainSet = new LinkedHashSet<>(mainPath);

        // Group off-main nodes by latest main-path predecessor
        Map<String, List<String>> branchHang = new LinkedHashMap<>();
        for (String node : topo) {
            if (mainSet.contains(node)) continue;
            String mpPred = latestMainPred(node, predecessors.get(node), mainPath);
            branchHang.computeIfAbsent(mpPred, k -> new ArrayList<>()).add(node);
        }

        // Attach no-predecessor off-nodes to first main-path node
        if (!mainPath.isEmpty() && branchHang.containsKey(null)) {
            branchHang.computeIfAbsent(mainPath.get(0), k -> new ArrayList<>())
                      .addAll(branchHang.remove(null));
        }

        Set<String> emitted = new LinkedHashSet<>();
        final String[] currentBranch = {"main"};
        final int[] branchCounter = {0};

        for (String node : mainPath) {
            if (!emitted.contains(node)) {
                emitNodeWithChannels(lines, node, procLookup, predecessors, channelBranch, currentBranch[0]);
                emitted.add(node);
            }

            for (String offNode : branchHang.getOrDefault(node, Collections.emptyList())) {
                branchCounter[0]++;
                String bname = "branch_" + branchCounter[0];
                lines.add("   branch " + bname);
                lines.add("   checkout " + bname);
                currentBranch[0] = bname;

                emitOffChainWithChannels(lines, offNode, mainSet, successors, predecessors,
                                          procLookup, channelBranch, currentBranch[0]);

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
                                                  channelBranch, currentBranch[0]);
                            emitted.add(step);
                        }
                    }
                    lines.add("   merge " + bname);
                    emitted.add(mergeTarget);
                    for (String[] cidExt : channelIdsWithExt(mergeTarget, procLookup)) {
                        String cid = cidExt[0]; String ext = cidExt[1];
                        String tagPart = ext != null ? " tag: \"" + ext + "\"" : "";
                        lines.add("   commit id: \"" + cid + "\" type: HIGHLIGHT" + tagPart);
                        channelBranch.put(cid, currentBranch[0]);
                    }
                } else {
                    lines.add("   checkout main");
                    currentBranch[0] = "main";
                }
            }
        }
    }

    private void emitOffChainWithChannels(List<String> lines, String start, Set<String> mainSet,
                                           Map<String, List<String>> successors,
                                           Map<String, List<String>> predecessors,
                                           Map<String, NfProcess> procLookup,
                                           Map<String, String> channelBranch,
                                           String currentBranch) {
        String cur = start;
        while (cur != null) {
            emitNodeWithChannels(lines, cur, procLookup, predecessors, channelBranch, currentBranch);
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
        // Catch any nodes not reached (cycles)
        nodes.stream().sorted().filter(n -> !result.contains(n)).forEach(result::add);
        return result;
    }

    private List<String> tracePath(String end, Map<String, String> pred) {
        List<String> path = new ArrayList<>();
        String cur = end;
        while (cur != null) {
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
}
