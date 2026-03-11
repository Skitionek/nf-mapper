package com.nfmapper.parser;

import com.nfmapper.model.*;
import nextflow.script.ast.*;
import nextflow.script.parser.ScriptAstBuilder;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.*;
import org.codehaus.groovy.control.*;
import org.codehaus.groovy.syntax.Token;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Parses Nextflow {@code .nf} files using the native Nextflow AST library
 * ({@code io.nextflow:nf-lang}).
 *
 * <p>The Nextflow script parser ({@link ScriptAstBuilder}) builds a {@link ScriptNode}
 * which is a {@link ModuleNode} subclass that exposes the Nextflow DSL constructs
 * directly via typed getters:
 *
 * <ul>
 *   <li>{@link ScriptNode#getProcesses()} – all {@link ProcessNode} instances, each with
 *       pre-split {@code directives}, {@code inputs}, and {@code outputs} blocks.</li>
 *   <li>{@link ScriptNode#getWorkflows()} – all {@link WorkflowNode} instances, each with
 *       {@code takes}, {@code main}, and {@code emits} blocks; {@link WorkflowNode#isEntry()}
 *       distinguishes the unnamed entry workflow.</li>
 *   <li>{@link ScriptNode#getIncludes()} – all {@link IncludeNode} instances, each holding
 *       the source path and a list of {@link IncludeModuleNode} name/alias pairs.</li>
 * </ul>
 *
 * <p>This avoids manually walking the raw Groovy AST and is far more robust than
 * pattern-matching on raw {@code MethodCallExpression} trees.
 */
public class NextflowParser {

    public ParsedPipeline parseFile(String filePath) throws IOException {
        String content = Files.readString(Path.of(filePath));
        return parseContent(content);
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

        if (!(module instanceof ScriptNode script)) return empty();

        List<NfProcess> processes = new ArrayList<>();
        List<NfWorkflow> workflows = new ArrayList<>();
        List<NfInclude> includes = new ArrayList<>();
        List<String[]> connections = new ArrayList<>();
        Set<String> knownProcesses = new LinkedHashSet<>();

        // --- Includes ---
        for (IncludeNode inc : script.getIncludes()) {
            String path = inc.source.getValue() instanceof String s ? s : String.valueOf(inc.source.getValue());
            List<String> imports = new ArrayList<>();
            for (IncludeModuleNode mod : inc.modules) {
                // Use alias when present (that is what callers will use), but also track
                // the original name so connections can be resolved correctly.
                String effective = (mod.alias != null && !mod.alias.isEmpty()) ? mod.alias : mod.name;
                imports.add(effective);
                knownProcesses.add(effective);
                // Also register the original name in knownProcesses for alias-less usage
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

        // --- Workflows ---
        for (WorkflowNode wf : script.getWorkflows()) {
            NfWorkflow nfWf = extractWorkflow(wf, knownProcesses, connections);
            workflows.add(nfWf);
            if (nfWf.getName() != null) {
                knownProcesses.add(nfWf.getName());
            }
        }

        return new ParsedPipeline(processes, workflows, includes, deduplicateConnections(connections));
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

        // directives block: container, conda, template (already separated by nf-lang)
        if (proc.directives instanceof BlockStatement bs) {
            for (Statement stmt : bs.getStatements()) {
                if (!(stmt instanceof ExpressionStatement es)) continue;
                if (!(es.getExpression() instanceof MethodCallExpression mce)) continue;
                String m = mce.getMethodAsString();
                switch (m) {
                    case "container" -> getFirstStringArg(mce).ifPresent(containers::add);
                    case "conda" -> getFirstStringArg(mce).ifPresent(v -> condas.addAll(splitConda(v)));
                    case "template" -> getFirstStringArg(mce).ifPresent(templates::add);
                }
            }
        }

        // inputs block (already separated by nf-lang)
        collectPathPatterns(proc.inputs, inputs);

        // outputs block (already separated by nf-lang)
        collectPathPatterns(proc.outputs, outputs);

        return new NfProcess(proc.getName(), containers, condas, templates, inputs, outputs);
    }

    // -------------------------------------------------------------------------
    // Workflow extraction
    // -------------------------------------------------------------------------

    private NfWorkflow extractWorkflow(WorkflowNode wf,
                                        Set<String> knownProcesses,
                                        List<String[]> connections) {
        String name = wf.isEntry() ? null : wf.getName();
        if (wf.main == null) return new NfWorkflow(name, Collections.emptyList());

        // Build channel variable map (two passes for transitivity)
        Map<String, String> channelVarMap = new LinkedHashMap<>();
        buildChannelVarMap(wf.main, knownProcesses, channelVarMap);
        buildChannelVarMap(wf.main, knownProcesses, channelVarMap);

        List<String> calls = new ArrayList<>();
        collectWorkflowCalls(wf.main, knownProcesses, channelVarMap, calls, connections);

        return new NfWorkflow(name, calls);
    }

    /**
     * Recursively walk the workflow main block, capturing process calls and connections.
     * Handles nested {@link IfStatement}, {@link WhileStatement}, and {@link ForStatement}
     * so that calls inside conditional blocks are captured.
     */
    private void collectWorkflowCalls(Statement stmt,
                                       Set<String> knownProcesses,
                                       Map<String, String> channelVarMap,
                                       List<String> calls,
                                       List<String[]> connections) {
        if (stmt == null) return;
        if (stmt instanceof BlockStatement bs) {
            for (Statement child : bs.getStatements()) {
                collectWorkflowCalls(child, knownProcesses, channelVarMap, calls, connections);
            }
        } else if (stmt instanceof IfStatement is) {
            collectWorkflowCalls(is.getIfBlock(), knownProcesses, channelVarMap, calls, connections);
            collectWorkflowCalls(is.getElseBlock(), knownProcesses, channelVarMap, calls, connections);
        } else if (stmt instanceof WhileStatement ws) {
            collectWorkflowCalls(ws.getLoopBlock(), knownProcesses, channelVarMap, calls, connections);
        } else if (stmt instanceof ForStatement fs) {
            collectWorkflowCalls(fs.getLoopBlock(), knownProcesses, channelVarMap, calls, connections);
        } else if (stmt instanceof ExpressionStatement es) {
            Expression expr = es.getExpression();
            if (expr instanceof MethodCallExpression mce) {
                String method = mce.getMethodAsString();
                if (method != null && knownProcesses.contains(method)) {
                    if (!calls.contains(method)) calls.add(method);
                    Set<String> outRefs = new LinkedHashSet<>();
                    for (Expression arg : getArgs(mce)) {
                        collectOutRefs(arg, knownProcesses, channelVarMap, outRefs);
                    }
                    for (String src : outRefs) {
                        connections.add(new String[]{src, method});
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
        if (stmt == null) return;
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
        if (expr == null) return;
        // Pattern 1: ch = PROC.out.x
        if (expr instanceof BinaryExpression be) {
            Token op = be.getOperation();
            if ("=".equals(op.getText()) && be.getLeftExpression() instanceof VariableExpression ve) {
                String varName = ve.getName();
                if (!channelVarMap.containsKey(varName)) {
                    Set<String> refs = new LinkedHashSet<>();
                    collectOutRefs(be.getRightExpression(), knownProcesses, channelVarMap, refs);
                    if (!refs.isEmpty()) channelVarMap.put(varName, refs.iterator().next());
                }
            }
        }
        // Pattern 2: expr.set { ch_var }
        if (expr instanceof MethodCallExpression mce && "set".equals(mce.getMethodAsString())) {
            List<Expression> args = getArgs(mce);
            if (!args.isEmpty() && args.get(0) instanceof ClosureExpression ce) {
                String varName = extractSingleVarFromClosure(ce);
                if (varName != null && !channelVarMap.containsKey(varName)) {
                    Set<String> refs = new LinkedHashSet<>();
                    collectOutRefs(mce.getObjectExpression(), knownProcesses, channelVarMap, refs);
                    if (!refs.isEmpty()) channelVarMap.put(varName, refs.iterator().next());
                }
            }
        }
    }

    private String extractSingleVarFromClosure(ClosureExpression ce) {
        if (!(ce.getCode() instanceof BlockStatement body)) return null;
        List<Statement> stmts = body.getStatements();
        if (stmts.isEmpty()) return null;
        if (!(stmts.get(0) instanceof ExpressionStatement es)) return null;
        if (es.getExpression() instanceof VariableExpression ve) {
            String name = ve.getName();
            if (!name.isEmpty() && Character.isLowerCase(name.charAt(0))) return name;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Out-reference detection
    // -------------------------------------------------------------------------

    private void collectOutRefs(Expression expr, Set<String> knownProcesses,
                                  Map<String, String> channelVarMap, Set<String> found) {
        if (expr == null) return;
        if (expr instanceof VariableExpression ve) {
            String name = ve.getName();
            if (channelVarMap.containsKey(name)) found.add(channelVarMap.get(name));
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
    // Path pattern extraction (input/output sections – already separated by nf-lang)
    // -------------------------------------------------------------------------

    private void collectPathPatterns(Statement stmt, List<String> result) {
        if (stmt == null) return;
        if (stmt instanceof BlockStatement bs) {
            for (Statement child : bs.getStatements()) {
                collectPathPatterns(child, result);
            }
        } else if (stmt instanceof ExpressionStatement es) {
            collectPathPatternsExpr(es.getExpression(), result);
        }
    }

    private void collectPathPatternsExpr(Expression expr, List<String> result) {
        if (expr == null) return;
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
        if (args instanceof TupleExpression te) return te.getExpressions();
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

    private static final Pattern SPACE_SPLIT = Pattern.compile("[ \\t]+");

    private List<String> splitConda(String value) {
        return List.of(SPACE_SPLIT.split(value.trim()));
    }

    private static final char CONN_KEY_SEP = '\u0000';

    private List<String[]> deduplicateConnections(List<String[]> connections) {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> result = new ArrayList<>();
        for (String[] conn : connections) {
            if (seen.add(conn[0] + CONN_KEY_SEP + conn[1])) result.add(conn);
        }
        return result;
    }

    private static ParsedPipeline empty() {
        return new ParsedPipeline(Collections.emptyList(), Collections.emptyList(),
                                  Collections.emptyList(), Collections.emptyList());
    }
}
