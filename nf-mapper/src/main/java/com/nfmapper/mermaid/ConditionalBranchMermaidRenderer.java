package com.nfmapper.mermaid;

import java.util.*;

import com.nfmapper.model.NfInclude;
import com.nfmapper.model.NfProcess;
import com.nfmapper.model.NfWorkflow;
import com.nfmapper.model.ParsedPipeline;

public class ConditionalBranchMermaidRenderer extends MermaidRenderer {

    private static final Map<String, Object> DEFAULT_GITGRAPH_CONFIG;
    static {
        DEFAULT_GITGRAPH_CONFIG = new LinkedHashMap<>();
        DEFAULT_GITGRAPH_CONFIG.put("showBranches", true);
        DEFAULT_GITGRAPH_CONFIG.put("parallelCommits", false);
    }

    private final MermaidRenderer fallbackDagRenderer;
    private final MermaidTheme theme;

    public ConditionalBranchMermaidRenderer() {
        this(new MermaidRenderer(), new NfCoreMermaidTheme());
    }

    public ConditionalBranchMermaidRenderer(MermaidRenderer fallbackDagRenderer, MermaidTheme theme) {
        this.fallbackDagRenderer = fallbackDagRenderer;
        this.theme = theme == null ? new NfCoreMermaidTheme() : theme;
    }

    @Override
    public String render(ParsedPipeline pipeline, String title, Map<String, Object> configOverrides) {
        if (!pipeline.getConnections().isEmpty()) {
            String base = fallbackDagRenderer.render(pipeline, title, configOverrides);
            return renameConditionalDagBranches(base);
        }

        List<String> lines = new ArrayList<>();

        if (title != null && !title.isEmpty()) {
            lines.add("---");
            lines.add("title: " + title);
            lines.add("---");
        }

        Map<String, Object> mergedGitGraph = new LinkedHashMap<>(DEFAULT_GITGRAPH_CONFIG);
        if (configOverrides != null)
            mergedGitGraph.putAll(configOverrides);

        lines.add("%%{init: {'theme': '" + theme.mermaidThemeName() + "', 'themeVariables': "
                + formatConfig(theme.themeVariables()) + ", 'gitGraph': "
                + formatConfig(mergedGitGraph) + "} }%%");
        lines.add("gitGraph LR:");
        lines.add("   checkout main");

        renderFlatByCondition(lines, pipeline);
        return String.join("\n", lines);
    }

    private String renameConditionalDagBranches(String rendered) {
        List<String> lines = new ArrayList<>(Arrays.asList(rendered.split("\\n", -1)));
        if (lines.isEmpty())
            return rendered;

        Set<String> usedBranchNames = new LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("branch ")) {
                usedBranchNames.add(trimmed.substring("branch ".length()));
            }
        }

        Map<String, String> branchRenameMap = new LinkedHashMap<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            String current = lines.get(i).trim();
            String next = lines.get(i + 1).trim();
            if (!current.startsWith("commit id: \"if: ") || !current.contains("\" type: REVERSE"))
                continue;
            if (!next.startsWith("branch "))
                continue;

            String conditionText = current.substring("commit id: \"if: ".length(),
                    current.indexOf("\" type: REVERSE"));
            String oldBranch = next.substring("branch ".length());
            String desired = branchName("if_" + conditionText, usedBranchNames);
            if (!oldBranch.equals(desired)) {
                branchRenameMap.put(oldBranch, desired);
            }
        }

        if (branchRenameMap.isEmpty())
            return rendered;

        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            for (Map.Entry<String, String> rename : branchRenameMap.entrySet()) {
                String from = rename.getKey();
                String to = rename.getValue();
                if (trimmed.equals("branch " + from)) {
                    lines.set(i, "   branch " + to);
                } else if (trimmed.equals("checkout " + from)) {
                    lines.set(i, "   checkout " + to);
                } else if (trimmed.equals("merge " + from)) {
                    lines.set(i, "   merge " + to);
                }
            }
        }

        return String.join("\n", lines);
    }

    private void renderFlatByCondition(List<String> lines, ParsedPipeline pipeline) {
        List<String> ordered = orderedCalls(pipeline);
        if (ordered.isEmpty())
            return;

        Map<String, NfProcess> procLookup = new LinkedHashMap<>();
        for (NfProcess p : pipeline.getProcesses())
            procLookup.put(p.getName(), p);

        emitMainFileRefCommits(lines, pipeline);

        Map<String, String> conditionBranch = new LinkedHashMap<>();
        Set<String> usedBranchNames = new LinkedHashSet<>();
        Map<String, String[]> conditionalInfo = pipeline.getConditionalInfo();

        for (String name : ordered) {
            String[] ci = conditionalInfo.get(name);
            if (ci == null) {
                emitProcess(lines, name, procLookup, conditionalInfo, Collections.emptySet());
                continue;
            }

            String conditionText = ci.length > 1 ? ci[1] : "conditional";
            String branch = conditionBranch.get(conditionText);
            if (branch == null) {
                lines.add("   commit id: \"if: " + conditionText + "\" type: REVERSE");
                branch = branchName("if_" + conditionText, usedBranchNames);
                conditionBranch.put(conditionText, branch);
                lines.add("   branch " + branch);
            }

            lines.add("   checkout " + branch);
            emitProcess(lines, name, procLookup, conditionalInfo, Set.of(ci[0]));
            lines.add("   checkout main");
        }
    }

    private List<String> orderedCalls(ParsedPipeline pipeline) {
        List<String> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        Set<String> namedWfNames = namedWorkflowNames(pipeline);
        Set<String> allProcessNames = new LinkedHashSet<>();
        for (NfProcess p : pipeline.getProcesses())
            allProcessNames.add(p.getName());
        for (NfInclude inc : pipeline.getIncludes()) {
            for (String imp : inc.getImports()) {
                if (!namedWfNames.contains(imp))
                    allProcessNames.add(imp);
            }
        }

        for (NfWorkflow wf : pipeline.getWorkflows()) {
            for (String call : wf.getCalls()) {
                if (allProcessNames.contains(call) && seen.add(call)) {
                    ordered.add(call);
                }
            }
        }

        for (NfProcess proc : pipeline.getProcesses()) {
            if (seen.add(proc.getName()))
                ordered.add(proc.getName());
        }

        return ordered;
    }

    private void emitProcess(List<String> lines,
            String procName,
            Map<String, NfProcess> procLookup,
            Map<String, String[]> conditionalInfo,
            Set<String> preEmittedGroups) {
        emitAggregatedInputHighlights(lines, procName, procLookup);

        String[] ci = conditionalInfo.get(procName);
        if (ci != null && !preEmittedGroups.contains(ci[0])) {
            lines.add("   commit id: \"if: " + ci[1] + "\" type: REVERSE");
        }

        String outputTags = buildOutputTagsSuffix(procName, procLookup);
        lines.add("   commit id: \"" + procName + "\"" + outputTags);
    }

    private void emitMainFileRefCommits(List<String> lines, ParsedPipeline pipeline) {
        for (String pattern : collectAllMainFileRefs(pipeline)) {
            String ext = fileExtension(pattern);
            String tagPart = ext != null ? " tag: \"" + ext + "\"" : "";
            lines.add("   commit id: \"input: " + pattern + "\" type: HIGHLIGHT" + tagPart);
        }
    }

    private List<String> collectAllMainFileRefs(ParsedPipeline pipeline) {
        List<String> refs = new ArrayList<>();
        for (NfWorkflow wf : pipeline.getWorkflows()) {
            for (String ref : wf.getMainFileRefs()) {
                if (!refs.contains(ref))
                    refs.add(ref);
            }
        }
        return refs;
    }

    private String fileExtension(String pattern) {
        if (pattern == null)
            return null;
        String p = pattern.replaceAll("^['\"]|['\"]$", "");
        int dotIdx = p.lastIndexOf('.');
        if (dotIdx >= 0 && dotIdx < p.length() - 1) {
            String ext = p.substring(dotIdx + 1);
            return ext.isEmpty() ? null : ext;
        }
        return null;
    }

    private void emitAggregatedInputHighlights(List<String> lines,
            String procName,
            Map<String, NfProcess> procLookup) {
        NfProcess proc = procLookup.get(procName);
        if (proc == null || proc.getInputs().isEmpty())
            return;
        List<String> inputs = proc.getInputs();
        int n = inputs.size();
        String cid = n == 1 ? procName + ": input: " + inputs.get(0) : procName + ": input: *";
        StringBuilder sb = new StringBuilder("   commit id: \"").append(cid).append("\" type: HIGHLIGHT");
        int tagsToShow = Math.min(n, 2);
        for (int i = 0; i < tagsToShow; i++) {
            sb.append(" tag: \"").append(inputs.get(i)).append("\"");
        }
        int remaining = n - tagsToShow;
        if (remaining > 0) {
            sb.append(" tag: \"+").append(remaining).append(" more\"");
        }
        lines.add(sb.toString());
    }

    private String buildOutputTagsSuffix(String procName, Map<String, NfProcess> procLookup) {
        NfProcess proc = procLookup.get(procName);
        if (proc == null || proc.getOutputs().isEmpty())
            return "";
        List<String> outputs = proc.getOutputs();
        int n = outputs.size();
        StringBuilder sb = new StringBuilder();
        int tagsToShow = Math.min(n, 2);
        for (int i = 0; i < tagsToShow; i++) {
            sb.append(" tag: \"").append(outputs.get(i)).append("\"");
        }
        int remaining = n - tagsToShow;
        if (remaining > 0) {
            sb.append(" tag: \"+").append(remaining).append(" more\"");
        }
        return sb.toString();
    }

    private String branchName(String baseName, Set<String> usedBranchNames) {
        String candidate = baseName.replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (candidate.isEmpty())
            candidate = "branch";
        if (usedBranchNames.add(candidate))
            return candidate;
        int i = 2;
        while (!usedBranchNames.add(candidate + "_" + i))
            i++;
        return candidate + "_" + i;
    }

    private Set<String> namedWorkflowNames(ParsedPipeline pipeline) {
        Set<String> names = new LinkedHashSet<>();
        for (NfWorkflow wf : pipeline.getWorkflows()) {
            if (wf.getName() != null)
                names.add(wf.getName());
        }
        return names;
    }

    private String formatConfig(Map<String, Object> config) {
        return formatConfig(config, 0);
    }

    private String formatConfig(Map<String, Object> config, int depth) {
        if (depth > 5)
            return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if (!first)
                sb.append(", ");
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
}
