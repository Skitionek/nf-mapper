package com.nfmapper.parser;

import com.nfmapper.model.*;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.*;
import org.codehaus.groovy.control.*;
import org.codehaus.groovy.syntax.Token;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class NextflowParser {

    public ParsedPipeline parseFile(String filePath) throws IOException {
        String content = Files.readString(Path.of(filePath));
        return parseContent(content);
    }

    public ParsedPipeline parseContent(String content) {
        if (content == null || content.isBlank()) {
            return empty();
        }

        // Tolerance set to max so the compiler continues parsing despite syntax errors
        // that arise from Nextflow-specific DSL constructs not valid in pure Groovy
        CompilerConfiguration config = new CompilerConfiguration();
        config.setTolerance(Integer.MAX_VALUE);
        CompilationUnit cu = new CompilationUnit(config);
        SourceUnit su;
        try {
            su = cu.addSource("pipeline.nf", content);
        } catch (Exception e) {
            return empty();
        }
        try {
            cu.compile(Phases.CONVERSION);
        } catch (Exception ignored) {
            // tolerance mode - continue with partial AST
        }

        ModuleNode module;
        try {
            module = su.getAST();
        } catch (Exception e) {
            return empty();
        }
        if (module == null) return empty();

        BlockStatement block = module.getStatementBlock();
        if (block == null) return empty();

        List<NfProcess> processes = new ArrayList<>();
        List<NfWorkflow> workflows = new ArrayList<>();
        List<NfInclude> includes = new ArrayList<>();
        List<String[]> connections = new ArrayList<>();
        Set<String> knownProcesses = new LinkedHashSet<>();

        for (Statement stmt : block.getStatements()) {
            if (!(stmt instanceof ExpressionStatement es)) continue;
            Expression expr = es.getExpression();
            if (!(expr instanceof MethodCallExpression mce)) continue;

            String method = mce.getMethodAsString();
            if ("process".equals(method)) {
                NfProcess proc = extractProcess(mce);
                if (proc != null) {
                    processes.add(proc);
                    knownProcesses.add(proc.getName());
                }
            } else if ("workflow".equals(method)) {
                NfWorkflow wf = extractWorkflow(mce, knownProcesses, connections);
                if (wf != null) {
                    workflows.add(wf);
                    if (wf.getName() != null) {
                        knownProcesses.add(wf.getName());
                    }
                }
            } else if ("from".equals(method)) {
                NfInclude inc = extractInclude(mce);
                if (inc != null) {
                    includes.add(inc);
                    knownProcesses.addAll(inc.getImports());
                }
            }
        }

        // Deduplicate connections preserving order
        List<String[]> uniqueConns = deduplicateConnections(connections);
        return new ParsedPipeline(processes, workflows, includes, uniqueConns);
    }

    // -------------------------------------------------------------------------
    // Process extraction
    // -------------------------------------------------------------------------

    private NfProcess extractProcess(MethodCallExpression processCall) {
        // process NAME { body }
        // → MCE("process", args=[MCE("NAME", args=[ClosureExpr])])
        List<Expression> args = getArgs(processCall);
        if (args.isEmpty()) return null;

        Expression firstArg = args.get(0);
        if (!(firstArg instanceof MethodCallExpression innerMce)) return null;

        String name = innerMce.getMethodAsString();
        if (name == null || name.isEmpty()) return null;

        ClosureExpression closure = findFirstClosure(getArgs(innerMce));
        if (closure == null) return null;
        if (!(closure.getCode() instanceof BlockStatement body)) return null;

        List<String> containers = new ArrayList<>();
        List<String> condas = new ArrayList<>();
        List<String> templates = new ArrayList<>();
        List<String> inputs = new ArrayList<>();
        List<String> outputs = new ArrayList<>();

        String currentSection = null;

        for (Statement stmt : body.getStatements()) {
            String label = getStatementLabel(stmt);
            if (label != null && !label.isEmpty()) {
                currentSection = label;
            }

            if (isSkipSection(currentSection)) continue;
            if (!(stmt instanceof ExpressionStatement es)) continue;
            Expression expr = es.getExpression();

            if (currentSection == null) {
                // Directives before any section label
                if (expr instanceof MethodCallExpression mce) {
                    String m = mce.getMethodAsString();
                    if ("container".equals(m)) {
                        String v = getFirstStringArg(mce);
                        if (v != null) containers.add(v);
                    } else if ("conda".equals(m)) {
                        String v = getFirstStringArg(mce);
                        if (v != null) condas.add(v);
                    } else if ("template".equals(m)) {
                        String v = getFirstStringArg(mce);
                        if (v != null) templates.add(v);
                    }
                }
            } else if ("input".equals(currentSection)) {
                collectPathPatterns(expr, inputs);
            } else if ("output".equals(currentSection)) {
                collectPathPatterns(expr, outputs);
            }
        }

        return new NfProcess(name, containers, condas, templates, inputs, outputs);
    }

    private boolean isSkipSection(String section) {
        return "script".equals(section) || "shell".equals(section)
                || "exec".equals(section) || "when".equals(section)
                || "stub".equals(section);
    }

    // -------------------------------------------------------------------------
    // Workflow extraction
    // -------------------------------------------------------------------------

    private NfWorkflow extractWorkflow(MethodCallExpression workflowMce,
                                        Set<String> knownProcesses,
                                        List<String[]> connections) {
        List<Expression> args = getArgs(workflowMce);
        if (args.isEmpty()) return null;

        Expression firstArg = args.get(0);

        if (firstArg instanceof ClosureExpression ce) {
            // Unnamed workflow: workflow { ... }
            return processWorkflowClosure(null, ce, knownProcesses, connections);
        } else if (firstArg instanceof MethodCallExpression innerMce) {
            // Named workflow: workflow NAME { ... }
            String name = innerMce.getMethodAsString();
            ClosureExpression closure = findFirstClosure(getArgs(innerMce));
            if (closure == null) return null;
            return processWorkflowClosure(name, closure, knownProcesses, connections);
        }
        return null;
    }

    private NfWorkflow processWorkflowClosure(String name, ClosureExpression closure,
                                               Set<String> knownProcesses,
                                               List<String[]> connections) {
        if (!(closure.getCode() instanceof BlockStatement body)) return new NfWorkflow(name, Collections.emptyList());

        // Build channel variable map (two passes for transitivity)
        Map<String, String> channelVarMap = new LinkedHashMap<>();
        buildChannelVarMap(body.getStatements(), knownProcesses, channelVarMap);
        buildChannelVarMap(body.getStatements(), knownProcesses, channelVarMap);

        List<String> calls = new ArrayList<>();
        String currentSection = null;

        for (Statement stmt : body.getStatements()) {
            String label = getStatementLabel(stmt);
            if (label != null && !label.isEmpty()) {
                currentSection = label;
            }

            // Process calls visible in main or no-section context
            boolean inMain = currentSection == null || "main".equals(currentSection);
            if (!inMain) continue;

            if (!(stmt instanceof ExpressionStatement es)) continue;
            Expression expr = es.getExpression();

            if (expr instanceof MethodCallExpression mce) {
                String method = mce.getMethodAsString();
                if (method != null && knownProcesses.contains(method)) {
                    if (!calls.contains(method)) calls.add(method);
                    // Detect connections from process.out references in args
                    List<Expression> callArgs = getArgs(mce);
                    Set<String> outRefs = new LinkedHashSet<>();
                    for (Expression arg : callArgs) {
                        collectOutRefs(arg, knownProcesses, channelVarMap, outRefs);
                    }
                    for (String src : outRefs) {
                        connections.add(new String[]{src, method});
                    }
                }
            }
        }

        return new NfWorkflow(name, calls);
    }

    // -------------------------------------------------------------------------
    // Channel variable map building
    // -------------------------------------------------------------------------

    private void buildChannelVarMap(List<Statement> stmts, Set<String> knownProcesses,
                                     Map<String, String> channelVarMap) {
        for (Statement stmt : stmts) {
            if (!(stmt instanceof ExpressionStatement es)) continue;
            Expression expr = es.getExpression();
            scanForChannelAssignments(expr, knownProcesses, channelVarMap);
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
                    Set<String> outRefs = new LinkedHashSet<>();
                    collectOutRefs(be.getRightExpression(), knownProcesses, channelVarMap, outRefs);
                    if (!outRefs.isEmpty()) {
                        channelVarMap.put(varName, outRefs.iterator().next());
                    }
                }
            }
            return;
        }

        // Pattern 2: expr.set { ch_var }
        if (expr instanceof MethodCallExpression mce && "set".equals(mce.getMethodAsString())) {
            List<Expression> args = getArgs(mce);
            if (!args.isEmpty() && args.get(0) instanceof ClosureExpression ce) {
                String varName = extractSingleVarFromClosure(ce);
                if (varName != null && !channelVarMap.containsKey(varName)) {
                    Set<String> outRefs = new LinkedHashSet<>();
                    collectOutRefs(mce.getObjectExpression(), knownProcesses, channelVarMap, outRefs);
                    if (!outRefs.isEmpty()) {
                        channelVarMap.put(varName, outRefs.iterator().next());
                    }
                }
            }
        }
    }

    private String extractSingleVarFromClosure(ClosureExpression ce) {
        if (!(ce.getCode() instanceof BlockStatement body)) return null;
        List<Statement> stmts = body.getStatements();
        if (stmts.isEmpty()) return null;
        Statement first = stmts.get(0);
        if (!(first instanceof ExpressionStatement es)) return null;
        Expression expr = es.getExpression();
        if (expr instanceof VariableExpression ve) {
            String name = ve.getName();
            // Only lowercase identifiers (channel vars are lowercase)
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
            if (channelVarMap.containsKey(name)) {
                found.add(channelVarMap.get(name));
            }
            return;
        }

        if (expr instanceof PropertyExpression pe) {
            Expression obj = pe.getObjectExpression();
            String prop = pe.getPropertyAsString();
            if ("out".equals(prop) && obj instanceof VariableExpression ve
                    && knownProcesses.contains(ve.getName())) {
                found.add(ve.getName());
                return;
            }
            collectOutRefs(obj, knownProcesses, channelVarMap, found);
            return;
        }

        if (expr instanceof MethodCallExpression mce) {
            collectOutRefs(mce.getObjectExpression(), knownProcesses, channelVarMap, found);
            for (Expression arg : getArgs(mce)) {
                collectOutRefs(arg, knownProcesses, channelVarMap, found);
            }
            return;
        }

        if (expr instanceof BinaryExpression be) {
            collectOutRefs(be.getLeftExpression(), knownProcesses, channelVarMap, found);
            collectOutRefs(be.getRightExpression(), knownProcesses, channelVarMap, found);
        }
    }

    // -------------------------------------------------------------------------
    // Path pattern extraction (for input/output sections)
    // -------------------------------------------------------------------------

    private void collectPathPatterns(Expression expr, List<String> result) {
        if (expr == null) return;

        if (expr instanceof MethodCallExpression mce) {
            String m = mce.getMethodAsString();
            if ("path".equals(m)) {
                // Find first string literal in args
                for (Expression arg : getArgs(mce)) {
                    if (arg instanceof ConstantExpression ce && ce.getValue() instanceof String s
                            && !s.isEmpty()) {
                        result.add(s);
                        break;
                    }
                }
                return; // Don't recurse into path() call
            }
            // Recurse into non-path calls
            collectPathPatterns(mce.getObjectExpression(), result);
            for (Expression arg : getArgs(mce)) {
                collectPathPatterns(arg, result);
            }
        } else if (expr instanceof BinaryExpression be) {
            collectPathPatterns(be.getLeftExpression(), result);
            collectPathPatterns(be.getRightExpression(), result);
        }
        // ConstantExpression, VariableExpression, etc. → nothing
    }

    // -------------------------------------------------------------------------
    // Include extraction
    // -------------------------------------------------------------------------

    private NfInclude extractInclude(MethodCallExpression fromMce) {
        // MCE("from", obj=MCE("include", args=[ClosureExpr]), args=[ConstantExpr(path)])
        List<Expression> fromArgs = getArgs(fromMce);
        if (fromArgs.isEmpty()) return null;

        String path = getFirstStringConst(fromArgs.get(0));
        if (path == null) return null;

        Expression obj = fromMce.getObjectExpression();
        if (!(obj instanceof MethodCallExpression includeMce)) return null;
        if (!"include".equals(includeMce.getMethodAsString())) return null;

        ClosureExpression closure = findFirstClosure(getArgs(includeMce));
        if (closure == null) return null;
        if (!(closure.getCode() instanceof BlockStatement closureBody)) return null;

        List<String> imports = new ArrayList<>();
        for (Statement stmt : closureBody.getStatements()) {
            if (!(stmt instanceof ExpressionStatement es)) continue;
            Expression expr = es.getExpression();
            if (expr instanceof VariableExpression ve) {
                imports.add(ve.getName());
            } else if (expr instanceof CastExpression ce) {
                // PROC_A as ALIAS → include with alias name
                String alias = ce.getType().getNameWithoutPackage();
                if (!alias.isEmpty()) imports.add(alias);
                // Also add original name
                if (ce.getExpression() instanceof VariableExpression origVe) {
                    String origName = origVe.getName();
                    if (!origName.isEmpty() && !imports.contains(origName)) {
                        imports.add(origName);
                    }
                }
            }
        }
        return new NfInclude(path, imports);
    }

    // -------------------------------------------------------------------------
    // AST helper utilities
    // -------------------------------------------------------------------------

    private List<Expression> getArgs(MethodCallExpression mce) {
        Expression args = mce.getArguments();
        if (args instanceof TupleExpression te) {
            return te.getExpressions();
        }
        return Collections.emptyList();
    }

    private ClosureExpression findFirstClosure(List<Expression> exprs) {
        for (Expression e : exprs) {
            if (e instanceof ClosureExpression ce) return ce;
        }
        return null;
    }

    private String getFirstStringArg(MethodCallExpression mce) {
        for (Expression arg : getArgs(mce)) {
            if (arg instanceof ConstantExpression ce && ce.getValue() instanceof String s) {
                return s;
            }
        }
        return null;
    }

    private String getFirstStringConst(Expression expr) {
        if (expr instanceof ConstantExpression ce && ce.getValue() instanceof String s) return s;
        return null;
    }

    private String getStatementLabel(Statement stmt) {
        // Groovy 4 Statement has getStatementLabel() returning String
        // Fall back to checking labels list if that API isn't available
        try {
            return stmt.getStatementLabel();
        } catch (NoSuchMethodError e) {
            // Fallback: try getStatementLabels() if API differs
            return null;
        }
    }

    /** Delimiter for connection deduplication keys; chosen as a character invalid in process names. */
    private static final char CONNECTION_KEY_DELIMITER = '\u0000';

    private List<String[]> deduplicateConnections(List<String[]> connections) {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> result = new ArrayList<>();
        for (String[] conn : connections) {
            String key = conn[0] + CONNECTION_KEY_DELIMITER + conn[1];
            if (seen.add(key)) result.add(conn);
        }
        return result;
    }

    private static ParsedPipeline empty() {
        return new ParsedPipeline(Collections.emptyList(), Collections.emptyList(),
                                  Collections.emptyList(), Collections.emptyList());
    }
}
