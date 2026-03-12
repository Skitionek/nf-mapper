package com.nfmapper.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.*;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Token;

import com.nfmapper.model.NfInclude;
import com.nfmapper.model.NfProcess;
import com.nfmapper.model.NfWorkflow;
import com.nfmapper.model.ParsedPipeline;

import nextflow.script.ast.*;
import nextflow.script.parser.ScriptAstBuilder;

/**
 * Parses Nextflow {@code .nf} files using the native Nextflow AST library
 * ({@code io.nextflow:nf-lang}).
 */
public class NextflowParser {

    public ParsedPipeline parseFile(String filePath) throws IOException {
        return parseFileInternal(Path.of(filePath).toAbsolutePath().normalize(),
                new LinkedHashSet<>());
    }

    /**
     * Internal recursive implementation for following {@code include} paths.
     * {@code visited} prevents cycles.
     */
    private ParsedPipeline parseFileInternal(Path filePath, Set<Path> visited) throws IOException {
        if (!visited.add(filePath))
            return empty();
        String content = Files.readString(filePath);
        Path baseDir = filePath.getParent();

        // Parse the raw content of this file (includes same-file sub-workflow
        // unfolding)
        ParsedPipeline main = parseContent(content);

        // Follow includes from this file and merge their content
        List<NfProcess> allProcesses = new ArrayList<>(main.getProcesses());
        List<NfWorkflow> allWorkflows = new ArrayList<>(main.getWorkflows());
        List<NfInclude> allIncludes = new ArrayList<>(main.getIncludes());
        List<String[]> allConnections = new ArrayList<>(main.getConnections());
        Map<String, String[]> allConditionalInfo = new LinkedHashMap<>(main.getConditionalInfo());

        Set<String> existingProcNames = new LinkedHashSet<>();
        allProcesses.forEach(p -> existingProcNames.add(p.getName()));
        Set<String> existingWfNames = new LinkedHashSet<>();
        allWorkflows.stream().filter(w -> w.getName() != null)
                .forEach(w -> existingWfNames.add(w.getName()));

        for (NfInclude inc : main.getIncludes()) {
            Path incPath = resolveIncludePath(baseDir, inc.getPath());
            if (incPath == null)
                continue;
            try {
                ParsedPipeline sub = parseFileInternal(incPath, visited);
                // Merge processes (deduplicate by name)
                sub.getProcesses().stream()
                        .filter(p -> existingProcNames.add(p.getName()))
                        .forEach(allProcesses::add);
                // Merge named workflows only (skip entry/unnamed workflows from included files)
                sub.getWorkflows().stream()
                        .filter(w -> w.getName() != null && existingWfNames.add(w.getName()))
                        .forEach(allWorkflows::add);
                // Merge connections and conditional info
                allConnections.addAll(sub.getConnections());
                sub.getConditionalInfo().forEach(allConditionalInfo::putIfAbsent);
            } catch (IOException ignored) {
                // Include file not found or unreadable – skip gracefully
            }
        }

        // Re-run sub-workflow unfolding with the full merged set of named workflows
        // (handles cross-file sub-workflow calls that couldn't be resolved per-file)
        Map<String, NfWorkflow> namedWfMap = new LinkedHashMap<>();
        allWorkflows.stream().filter(w -> w.getName() != null)
                .forEach(w -> namedWfMap.put(w.getName(), w));
        List<String[]> resolvedConns = unfoldSubWorkflowConnections(
                deduplicateConnections(allConnections), namedWfMap);

        return new ParsedPipeline(allProcesses, allWorkflows, allIncludes,
                resolvedConns, allConditionalInfo);
    }

    public ParsedPipeline parseContent(String content) {
        if (content == null || content.isBlank()) {
            return empty();
        }

        CompilerConfiguration config = new CompilerConfiguration();
        config.setTolerance(Integer.MAX_VALUE);
        SourceUnit sourceUnit = new SourceUnit(
                "pipeline.nf", content, config, null, new ErrorCollector(config));

        ScriptAstBuilder builder = new ScriptAstBuilder(sourceUnit);
        ModuleNode module;
        try {
            module = builder.buildAST();
        } catch (Exception e) {
            return empty();
        }

        if (!(module instanceof ScriptNode script))
            return empty();

        List<NfProcess> processes = new ArrayList<>();
        List<NfWorkflow> workflows = new ArrayList<>();
        List<NfInclude> includes = new ArrayList<>();
        List<String[]> connections = new ArrayList<>();
        Map<String, String[]> conditionalInfo = new LinkedHashMap<>();
        Set<String> knownProcesses = new LinkedHashSet<>();

        // --- Includes ---
        for (IncludeNode inc : script.getIncludes()) {
            String path = inc.source.getValue() instanceof String s ? s : String.valueOf(inc.source.getValue());
            List<String> imports = new ArrayList<>();
            for (IncludeModuleNode mod : inc.modules) {
                String effective = (mod.alias != null && !mod.alias.isEmpty()) ? mod.alias : mod.name;
                imports.add(effective);
                knownProcesses.add(effective);
                if (mod.alias != null && !mod.alias.isEmpty()) {
                    knownProcesses.add(mod.name);
                }
            }
            includes.add(new NfInclude(path, imports));
        }

        // --- Processes ---
        for (ProcessNode proc : script.getProcesses()) {
            NfProcess nfProc = extractProcess(proc);
            processes.add(nfProc);
            knownProcesses.add(nfProc.getName());
        }

        // --- Pre-pass: register ALL named workflow names so forward-references are
        // detected ---
        for (WorkflowNode wf : script.getWorkflows()) {
            if (!wf.isEntry() && wf.getName() != null) {
                knownProcesses.add(wf.getName());
            }
        }

        // --- Workflows ---
        int[] ifGroupCounter = { 0 };
        for (WorkflowNode wf : script.getWorkflows()) {
            NfWorkflow nfWf = extractWorkflow(wf, knownProcesses, connections,
                    conditionalInfo, ifGroupCounter);
            workflows.add(nfWf);
            if (nfWf.getName() != null) {
                knownProcesses.add(nfWf.getName());
            }
        }

        // --- Unfold same-file named sub-workflow calls ---
        // Replace any connection endpoint that is a named sub-workflow with that
        // sub-workflow's constituent processes, producing a fully-resolved DAG.
        Map<String, NfWorkflow> namedWfMap = new LinkedHashMap<>();
        workflows.stream().filter(w -> w.getName() != null)
                .forEach(w -> namedWfMap.put(w.getName(), w));
        List<String[]> resolvedConns = unfoldSubWorkflowConnections(
                deduplicateConnections(connections), namedWfMap);

        return new ParsedPipeline(processes, workflows, includes,
                resolvedConns, conditionalInfo);
    }

    // -------------------------------------------------------------------------
    // Sub-workflow unfolding
    // -------------------------------------------------------------------------

    /**
     * Iteratively replace any connection endpoint that is a named sub-workflow with
     * that sub-workflow's constituent entry/exit processes, until no named workflow
     * nodes remain in the connection graph.
     *
     * <p>
     * The loop is bounded to {@code namedWorkflows.size() * 2 + 1} iterations so
     * that mutually-recursive or self-referential workflow call graphs (which are
     * invalid in Nextflow but may appear through include-merging artefacts) cannot
     * cause an infinite loop. Any connection whose endpoints are still named
     * workflows after the bound is exceeded is dropped from the result rather than
     * left as an unresolved node.
     *
     * <p>
     * Self-loop edges ({@code A → A}) are never added to the expansion result;
     * they would create a cycle in the downstream DAG rendering.
     */
    private List<String[]> unfoldSubWorkflowConnections(List<String[]> connections,
            Map<String, NfWorkflow> namedWorkflows) {
        if (namedWorkflows.isEmpty())
            return connections;
        // Each iteration resolves one level of nesting for either src or dst
        // (destination takes priority in the expansion — src is only expanded on the
        // *next* pass after the destination is fully resolved). In the acyclic case,
        // a connection [X, Wk] where Wk is nested k-levels deep needs k passes to
        // fully resolve the destination, plus up to another k passes for the source
        // side. Using namedWorkflows.size() * 2 + 1 as the bound is therefore a
        // conservative but tight upper-bound for acyclic topologies, and simply caps
        // the work for cyclic (invalid) topologies.
        int maxIterations = namedWorkflows.size() * 2 + 1;
        boolean changed = true;
        List<String[]> result = new ArrayList<>(connections);
        while (changed && maxIterations-- > 0) {
            changed = false;
            List<String[]> next = new ArrayList<>();
            for (String[] conn : result) {
                NfWorkflow srcWf = namedWorkflows.get(conn[0]);
                NfWorkflow dstWf = namedWorkflows.get(conn[1]);
                if (dstWf != null) {
                    if (!dstWf.getCalls().isEmpty()) {
                        // X -> SubWorkflow: expand to X -> [entry processes of SubWorkflow]
                        for (String entry : workflowEntryProcesses(dstWf, result)) {
                            // Skip self-loops; they create cycles in the DAG.
                            if (!entry.equals(conn[0])) {
                                next.add(new String[] { conn[0], entry });
                            }
                        }
                    }
                    // If dstWf has no calls (empty body), drop the connection entirely –
                    // an empty workflow contributes no visible nodes.
                    changed = true;
                } else if (srcWf != null) {
                    if (!srcWf.getCalls().isEmpty()) {
                        // SubWorkflow -> Y: expand to [exit processes of SubWorkflow] -> Y
                        for (String exit : workflowExitProcesses(srcWf, result)) {
                            // Skip self-loops; they create cycles in the DAG.
                            if (!exit.equals(conn[1])) {
                                next.add(new String[] { exit, conn[1] });
                            }
                        }
                    }
                    // If srcWf has no calls, drop the connection similarly.
                    changed = true;
                } else {
                    next.add(conn);
                }
            }
            result = next;
        }
        // Remove any connections whose endpoints are still named workflow names – these
        // could not be resolved (e.g. due to a cycle) and would produce orphan nodes.
        Set<String> wfNames = namedWorkflows.keySet();
        result = result.stream()
                .filter(c -> !wfNames.contains(c[0]) && !wfNames.contains(c[1]))
                .collect(java.util.stream.Collectors.toList());
        return deduplicateConnections(result);
    }

    /**
     * Returns the calls within {@code wf} that have no predecessor inside the
     * same workflow (i.e., the "entry" nodes of the sub-workflow).
     * Falls back to all calls if none can be identified.
     */
    private List<String> workflowEntryProcesses(NfWorkflow wf, List<String[]> allConns) {
        Set<String> callSet = new LinkedHashSet<>(wf.getCalls());
        Set<String> hasPred = new LinkedHashSet<>();
        for (String[] c : allConns) {
            if (callSet.contains(c[0]) && callSet.contains(c[1]))
                hasPred.add(c[1]);
        }
        List<String> entries = new ArrayList<>();
        for (String call : wf.getCalls()) {
            if (!hasPred.contains(call))
                entries.add(call);
        }
        return entries.isEmpty() ? new ArrayList<>(wf.getCalls()) : entries;
    }

    /**
     * Returns the calls within {@code wf} that have no successor inside the
     * same workflow (i.e., the "exit" nodes of the sub-workflow).
     * Falls back to all calls if none can be identified.
     */
    private List<String> workflowExitProcesses(NfWorkflow wf, List<String[]> allConns) {
        Set<String> callSet = new LinkedHashSet<>(wf.getCalls());
        Set<String> hasSucc = new LinkedHashSet<>();
        for (String[] c : allConns) {
            if (callSet.contains(c[0]) && callSet.contains(c[1]))
                hasSucc.add(c[0]);
        }
        List<String> exits = new ArrayList<>();
        for (String call : wf.getCalls()) {
            if (!hasSucc.contains(call))
                exits.add(call);
        }
        return exits.isEmpty() ? new ArrayList<>(wf.getCalls()) : exits;
    }

    // -------------------------------------------------------------------------
    // Include path resolution
    // -------------------------------------------------------------------------

    /**
     * Resolve an include path relative to {@code baseDir}.
     * Tries the path as-is, then with {@code .nf} appended, then with
     * {@code /main.nf} appended. Returns {@code null} if the file cannot be
     * found or the path contains dynamic expressions.
     *
     * <p>
     * <b>Note:</b> paths containing {@code $} or {@code {}} are treated as
     * dynamic (e.g. {@code "${params.modules_dir}/fastqc"}) and are skipped.
     * This heuristic may incorrectly skip valid paths whose file name happens to
     * contain a literal dollar sign, but such names are extremely rare in
     * Nextflow pipelines.
     */
    private Path resolveIncludePath(Path baseDir, String includePath) {
        if (includePath == null || includePath.contains("$") || includePath.contains("{")) {
            return null; // skip dynamic paths
        }
        Path base = baseDir.resolve(includePath).normalize();
        if (Files.isRegularFile(base))
            return base;
        Path withNf = Path.of(base.toString() + ".nf");
        if (Files.isRegularFile(withNf))
            return withNf;
        Path mainNf = base.resolve("main.nf");
        if (Files.isRegularFile(mainNf))
            return mainNf;
        return null;
    }

    // -------------------------------------------------------------------------
    // Process extraction
    // -------------------------------------------------------------------------

    private NfProcess extractProcess(ProcessNode proc) {
        List<String> containers = new ArrayList<>();
        List<String> condas = new ArrayList<>();
        List<String> templates = new ArrayList<>();
        List<String> inputs = new ArrayList<>();
        List<String> outputs = new ArrayList<>();

        if (proc.directives instanceof BlockStatement bs) {
            for (Statement stmt : bs.getStatements()) {
                if (!(stmt instanceof ExpressionStatement es))
                    continue;
                if (!(es.getExpression() instanceof MethodCallExpression mce))
                    continue;
                String m = mce.getMethodAsString();
                switch (m) {
                    case "container" -> getFirstStringArg(mce).ifPresent(containers::add);
                    case "conda" -> getFirstStringArg(mce).ifPresent(v -> condas.addAll(splitConda(v)));
                    case "template" -> getFirstStringArg(mce).ifPresent(templates::add);
                }
            }
        }

        collectPathPatterns(proc.inputs, inputs);
        collectPathPatterns(proc.outputs, outputs);

        return new NfProcess(proc.getName(), containers, condas, templates, inputs, outputs);
    }

    // -------------------------------------------------------------------------
    // Workflow extraction
    // -------------------------------------------------------------------------

    private NfWorkflow extractWorkflow(WorkflowNode wf,
            Set<String> knownProcesses,
            List<String[]> connections,
            Map<String, String[]> conditionalInfo,
            int[] ifGroupCounter) {
        String name = wf.isEntry() ? null : wf.getName();
        if (wf.main == null)
            return new NfWorkflow(name, Collections.emptyList());

        Map<String, String> channelVarMap = new LinkedHashMap<>();
        buildChannelVarMap(wf.main, knownProcesses, channelVarMap);

        List<String> calls = new ArrayList<>();
        collectWorkflowCalls(wf.main, knownProcesses, channelVarMap, calls, connections,
                conditionalInfo, null, ifGroupCounter);

        List<String> mainFileRefs = new ArrayList<>();
        collectMainFileRefs(wf.main, mainFileRefs);

        return new NfWorkflow(name, calls, mainFileRefs);
    }

    /**
     * Recursively walk the workflow main block, capturing process calls and
     * connections.
     * Calls inside {@code if}/{@code else} blocks are tagged in
     * {@code conditionalInfo}
     * with a group-id and the condition text extracted from the
     * {@link IfStatement}.
     *
     * @param conditionContext {@code null} when not inside any {@code if} block;
     *                         otherwise {@code "groupId:conditionText"}.
     */
    private void collectWorkflowCalls(Statement stmt,
            Set<String> knownProcesses,
            Map<String, String> channelVarMap,
            List<String> calls,
            List<String[]> connections,
            Map<String, String[]> conditionalInfo,
            String conditionContext,
            int[] ifGroupCounter) {
        if (stmt == null)
            return;
        if (stmt instanceof BlockStatement bs) {
            for (Statement child : bs.getStatements()) {
                collectWorkflowCalls(child, knownProcesses, channelVarMap, calls, connections,
                        conditionalInfo, conditionContext, ifGroupCounter);
            }
        } else if (stmt instanceof IfStatement is) {
            int groupId = ifGroupCounter[0]++;
            String condText = extractConditionText(is);
            String ifContext = groupId + ":" + condText;
            collectWorkflowCalls(is.getIfBlock(), knownProcesses, channelVarMap, calls,
                    connections, conditionalInfo, ifContext, ifGroupCounter);
            if (is.getElseBlock() != null) {
                int elseGroupId = ifGroupCounter[0]++;
                String elseContext = elseGroupId + ":else(" + condText + ")";
                collectWorkflowCalls(is.getElseBlock(), knownProcesses, channelVarMap, calls,
                        connections, conditionalInfo, elseContext, ifGroupCounter);
            }
        } else if (stmt instanceof WhileStatement ws) {
            collectWorkflowCalls(ws.getLoopBlock(), knownProcesses, channelVarMap, calls,
                    connections, conditionalInfo, conditionContext, ifGroupCounter);
        } else if (stmt instanceof ForStatement fs) {
            collectWorkflowCalls(fs.getLoopBlock(), knownProcesses, channelVarMap, calls,
                    connections, conditionalInfo, conditionContext, ifGroupCounter);
        } else if (stmt instanceof ExpressionStatement es) {
            Expression expr = es.getExpression();
            if (expr instanceof MethodCallExpression mce) {
                String method = mce.getMethodAsString();
                if (method != null && knownProcesses.contains(method)) {
                    if (!calls.contains(method))
                        calls.add(method);
                    if (conditionContext != null && !conditionalInfo.containsKey(method)) {
                        String[] parts = conditionContext.split(":", 2);
                        conditionalInfo.put(method, parts);
                    }
                    Set<String> outRefs = new LinkedHashSet<>();
                    for (Expression arg : getArgs(mce)) {
                        collectOutRefs(arg, knownProcesses, channelVarMap, outRefs);
                    }
                    for (String src : outRefs) {
                        connections.add(new String[] { src, method });
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Channel variable map
    // -------------------------------------------------------------------------

    private void buildChannelVarMap(Statement stmt, Set<String> knownProcesses,
            Map<String, String> channelVarMap) {
        if (stmt == null)
            return;
        if (stmt instanceof BlockStatement bs) {
            for (Statement child : bs.getStatements()) {
                buildChannelVarMap(child, knownProcesses, channelVarMap);
            }
        } else if (stmt instanceof IfStatement is) {
            buildChannelVarMap(is.getIfBlock(), knownProcesses, channelVarMap);
            buildChannelVarMap(is.getElseBlock(), knownProcesses, channelVarMap);
        } else if (stmt instanceof WhileStatement ws) {
            buildChannelVarMap(ws.getLoopBlock(), knownProcesses, channelVarMap);
        } else if (stmt instanceof ForStatement fs) {
            buildChannelVarMap(fs.getLoopBlock(), knownProcesses, channelVarMap);
        } else if (stmt instanceof ExpressionStatement es) {
            scanForChannelAssignments(es.getExpression(), knownProcesses, channelVarMap);
        }
    }

    private void scanForChannelAssignments(Expression expr, Set<String> knownProcesses,
            Map<String, String> channelVarMap) {
        if (expr == null)
            return;
        if (expr instanceof BinaryExpression be) {
            Token op = be.getOperation();
            if ("=".equals(op.getText()) && be.getLeftExpression() instanceof VariableExpression ve) {
                String varName = ve.getName();
                if (!channelVarMap.containsKey(varName)) {
                    Set<String> refs = new LinkedHashSet<>();
                    collectOutRefs(be.getRightExpression(), knownProcesses, channelVarMap, refs);
                    if (!refs.isEmpty())
                        channelVarMap.put(varName, refs.iterator().next());
                }
            }
        }
        if (expr instanceof MethodCallExpression mce && "set".equals(mce.getMethodAsString())) {
            List<Expression> args = getArgs(mce);
            if (!args.isEmpty() && args.get(0) instanceof ClosureExpression ce) {
                String varName = extractSingleVarFromClosure(ce);
                if (varName != null && !channelVarMap.containsKey(varName)) {
                    Set<String> refs = new LinkedHashSet<>();
                    collectOutRefs(mce.getObjectExpression(), knownProcesses, channelVarMap, refs);
                    if (!refs.isEmpty())
                        channelVarMap.put(varName, refs.iterator().next());
                }
            }
        }
    }

    private String extractSingleVarFromClosure(ClosureExpression ce) {
        if (!(ce.getCode() instanceof BlockStatement body))
            return null;
        List<Statement> stmts = body.getStatements();
        if (stmts.isEmpty())
            return null;
        if (!(stmts.get(0) instanceof ExpressionStatement es))
            return null;
        if (es.getExpression() instanceof VariableExpression ve) {
            String name = ve.getName();
            if (!name.isEmpty() && Character.isLowerCase(name.charAt(0)))
                return name;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Out-reference detection
    // -------------------------------------------------------------------------

    private void collectOutRefs(Expression expr, Set<String> knownProcesses,
            Map<String, String> channelVarMap, Set<String> found) {
        if (expr == null)
            return;
        if (expr instanceof VariableExpression ve) {
            String name = ve.getName();
            if (channelVarMap.containsKey(name))
                found.add(channelVarMap.get(name));
        } else if (expr instanceof PropertyExpression pe) {
            Expression obj = pe.getObjectExpression();
            if ("out".equals(pe.getPropertyAsString())
                    && obj instanceof VariableExpression ve
                    && knownProcesses.contains(ve.getName())) {
                found.add(ve.getName());
            } else {
                collectOutRefs(obj, knownProcesses, channelVarMap, found);
            }
        } else if (expr instanceof MethodCallExpression mce) {
            collectOutRefs(mce.getObjectExpression(), knownProcesses, channelVarMap, found);
            for (Expression arg : getArgs(mce)) {
                collectOutRefs(arg, knownProcesses, channelVarMap, found);
            }
        } else if (expr instanceof BinaryExpression be) {
            collectOutRefs(be.getLeftExpression(), knownProcesses, channelVarMap, found);
            collectOutRefs(be.getRightExpression(), knownProcesses, channelVarMap, found);
        }
    }

    // -------------------------------------------------------------------------
    // Main-block file reference extraction
    // -------------------------------------------------------------------------

    private static final Set<String> CHANNEL_FILE_METHODS = Set.of(
            "fromPath", "fromFilePairs", "fromSRA", "fromFastq");

    /**
     * Recursively walk a workflow {@code main:} block and collect string-literal
     * file patterns found in {@code Channel.fromPath("pattern")},
     * {@code Channel.fromFilePairs("pattern")}, and {@code file("pattern")} calls.
     */
    private void collectMainFileRefs(Statement stmt, List<String> result) {
        if (stmt == null)
            return;
        if (stmt instanceof BlockStatement bs) {
            for (Statement child : bs.getStatements()) {
                collectMainFileRefs(child, result);
            }
        } else if (stmt instanceof IfStatement is) {
            collectMainFileRefs(is.getIfBlock(), result);
            collectMainFileRefs(is.getElseBlock(), result);
        } else if (stmt instanceof WhileStatement ws) {
            collectMainFileRefs(ws.getLoopBlock(), result);
        } else if (stmt instanceof ForStatement fs) {
            collectMainFileRefs(fs.getLoopBlock(), result);
        } else if (stmt instanceof ExpressionStatement es) {
            collectMainFileRefsExpr(es.getExpression(), result);
        }
    }

    private void collectMainFileRefsExpr(Expression expr, List<String> result) {
        if (expr == null)
            return;
        if (expr instanceof MethodCallExpression mce) {
            String method = mce.getMethodAsString();
            if (CHANNEL_FILE_METHODS.contains(method) || "file".equals(method)) {
                // getFirstStringArg only returns ConstantExpression string values,
                // so GString interpolations (e.g. file(params.x)) are already excluded.
                getFirstStringArg(mce).filter(s -> !s.isBlank()).ifPresent(s -> addUniqueRef(s, result));
            }
            // Recurse into object expression and arguments to find nested channel calls
            collectMainFileRefsExpr(mce.getObjectExpression(), result);
            for (Expression arg : getArgs(mce)) {
                collectMainFileRefsExpr(arg, result);
            }
        } else if (expr instanceof BinaryExpression be) {
            collectMainFileRefsExpr(be.getLeftExpression(), result);
            collectMainFileRefsExpr(be.getRightExpression(), result);
        } else if (expr instanceof ClosureExpression ce) {
            if (ce.getCode() instanceof BlockStatement cbs) {
                collectMainFileRefs(cbs, result);
            }
        }
    }

    /**
     * Add {@code ref} to {@code list} only if not already present, preserving
     * insertion order.
     */
    private static void addUniqueRef(String ref, List<String> list) {
        if (!list.contains(ref))
            list.add(ref);
    }

    // -------------------------------------------------------------------------
    // Path pattern extraction
    // -------------------------------------------------------------------------

    private void collectPathPatterns(Statement stmt, List<String> result) {
        if (stmt == null)
            return;
        if (stmt instanceof BlockStatement bs) {
            for (Statement child : bs.getStatements()) {
                collectPathPatterns(child, result);
            }
        } else if (stmt instanceof ExpressionStatement es) {
            collectPathPatternsExpr(es.getExpression(), result);
        }
    }

    private void collectPathPatternsExpr(Expression expr, List<String> result) {
        if (expr == null)
            return;
        if (expr instanceof MethodCallExpression mce) {
            if ("path".equals(mce.getMethodAsString())) {
                for (Expression arg : getArgs(mce)) {
                    if (arg instanceof ConstantExpression ce && ce.getValue() instanceof String s
                            && !s.isEmpty()) {
                        result.add(s);
                        break;
                    }
                }
                return;
            }
            collectPathPatternsExpr(mce.getObjectExpression(), result);
            for (Expression arg : getArgs(mce)) {
                collectPathPatternsExpr(arg, result);
            }
        }
    }

    // -------------------------------------------------------------------------
    // AST helpers
    // -------------------------------------------------------------------------

    private List<Expression> getArgs(MethodCallExpression mce) {
        Expression args = mce.getArguments();
        if (args instanceof TupleExpression te)
            return te.getExpressions();
        return Collections.emptyList();
    }

    private Optional<String> getFirstStringArg(MethodCallExpression mce) {
        for (Expression arg : getArgs(mce)) {
            if (arg instanceof ConstantExpression ce && ce.getValue() instanceof String s) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /**
     * Extract a human-readable condition text from an {@link IfStatement}.
     * The result is truncated to 50 chars and sanitised for use as a Mermaid commit
     * ID.
     */
    private String extractConditionText(IfStatement is) {
        try {
            String text = is.getBooleanExpression().getExpression().getText();
            text = text.replaceAll("\\s+", " ").trim();
            text = text.replaceAll("\\bthis\\.", "");
            text = text.replace("\"", "'").replace("\\", "/");
            if (text.length() > 50)
                text = text.substring(0, 47) + "...";
            return text.isEmpty() ? "condition" : text;
        } catch (NullPointerException | UnsupportedOperationException e) {
            // AST node may lack a text representation – fall back to a generic label
            return "condition";
        }
    }

    private static final Pattern SPACE_SPLIT = Pattern.compile("[ \\t]+");

    private List<String> splitConda(String value) {
        return List.of(SPACE_SPLIT.split(value.trim()));
    }

    private static final char CONN_KEY_SEP = '\u0000';

    private List<String[]> deduplicateConnections(List<String[]> connections) {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> result = new ArrayList<>();
        for (String[] conn : connections) {
            // Skip self-loops (A → A): they would introduce cycles into the DAG.
            if (conn[0].equals(conn[1]))
                continue;
            if (seen.add(conn[0] + CONN_KEY_SEP + conn[1]))
                result.add(conn);
        }
        return result;
    }

    private static ParsedPipeline empty() {
        return new ParsedPipeline(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
    }
}
