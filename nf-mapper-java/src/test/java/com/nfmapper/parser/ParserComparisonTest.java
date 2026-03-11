package com.nfmapper.parser;

import com.nfmapper.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-validates the Java parser (native Groovy 4 AST) against the Python parser
 * (groovy_parser library) for every fixture .nf file.
 *
 * <p>For each fixture the test:
 * <ol>
 *   <li>Invokes the Python {@code nf_mapper.parser} via a subprocess and captures the
 *       extracted structural fields (process names, workflow names, include paths,
 *       connections, per-process output channel patterns) as a simple line-based
 *       key=value report.</li>
 *   <li>Runs the Java {@link NextflowParser} on the same file.</li>
 *   <li>Asserts that both parsers agree on every structural field.</li>
 * </ol>
 *
 * <p>If Python or the {@code nf_mapper} package is unavailable the test is skipped
 * automatically so it does not block pure-Java CI environments.
 */
class ParserComparisonTest {

    private static final NextflowParser JAVA_PARSER = new NextflowParser();

    /** Absolute path to the shared fixture directory (Python + Java tests use the same files). */
    private static final String FIXTURES_DIR =
        Paths.get(System.getProperty("user.dir"), "..", "tests", "fixtures").normalize().toString();

    /** Python script that reports the parsed pipeline structure in a line-based format. */
    private static final String PYTHON_SCRIPT = String.join("\n",
        "import sys, json",
        "from nf_mapper.parser import parse_nextflow_file",
        "p = parse_nextflow_file(sys.argv[1])",
        "print('PROCESSES:' + ','.join(sorted(pr.name for pr in p.processes)))",
        "print('WORKFLOWS:' + ','.join(sorted(str(wf.name) for wf in p.workflows)))",
        "print('INCLUDES:' + ','.join(sorted(inc.path for inc in p.includes)))",
        "print('CONNECTIONS:' + ','.join(sorted(c[0]+'->'+c[1] for c in p.connections)))",
        "for pr in sorted(p.processes, key=lambda x: x.name):",
        "    if pr.outputs:",
        "        print('OUTPUTS:' + pr.name + ':' + ','.join(sorted(pr.outputs)))"
    );

    // -------------------------------------------------------------------------
    // Availability check
    // -------------------------------------------------------------------------

    /** Returns true when Python 3 and the nf_mapper package can be found at test time. */
    private static boolean pythonAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", "-c",
                    "from nf_mapper.parser import parse_nextflow_file; print('ok')");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes()).trim();
            return proc.waitFor() == 0 && out.equals("ok");
        } catch (Exception e) {
            return false;
        }
    }

    private static final boolean PYTHON_OK = pythonAvailable();

    // -------------------------------------------------------------------------
    // Parameterised comparison tests
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "minimal_process.nf",
        "simple_workflow.nf",
        "complex_workflow.nf",
        "nf_core_fastqc_module.nf",
        "nf_core_fetchngs_sra.nf",
    })
    void compareJavaAndPythonParsers(String fixture) throws Exception {
        assumePythonAvailable();

        String fixturePath = FIXTURES_DIR + File.separator + fixture;

        // --- Python parser output ---
        Map<String, String> pyResult = runPythonParser(fixturePath);

        // --- Java parser output ---
        ParsedPipeline javaPipeline = JAVA_PARSER.parseFile(fixturePath);
        Map<String, String> javaResult = toReport(javaPipeline);

        // --- Compare structural fields ---
        assertSameField("PROCESSES", pyResult, javaResult, fixture);
        assertSameField("WORKFLOWS", pyResult, javaResult, fixture);
        assertSameField("INCLUDES",  pyResult, javaResult, fixture);
        assertSameField("CONNECTIONS", pyResult, javaResult, fixture);

        // Compare per-process output channel patterns
        for (Map.Entry<String, String> entry : pyResult.entrySet()) {
            if (entry.getKey().startsWith("OUTPUTS:")) {
                assertSameField(entry.getKey(), pyResult, javaResult, fixture);
            }
        }
        // Also verify Java reports no extra OUTPUTS entries that Python didn't report
        for (Map.Entry<String, String> entry : javaResult.entrySet()) {
            if (entry.getKey().startsWith("OUTPUTS:")) {
                assertTrue(pyResult.containsKey(entry.getKey()),
                    "[" + fixture + "] Java reports unexpected output channels for "
                    + entry.getKey() + " = " + entry.getValue()
                    + " (Python had none for this process)");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assumePythonAvailable() {
        org.junit.jupiter.api.Assumptions.assumeTrue(PYTHON_OK,
            "Skipping comparison test: Python nf_mapper package not available");
    }

    /**
     * Run the Python parser on {@code fixturePath} and return a map of
     * {@code FIELD_KEY -> comma-sorted-value-string}.
     */
    private Map<String, String> runPythonParser(String fixturePath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python3", "-c", PYTHON_SCRIPT, fixturePath);
        pb.redirectErrorStream(false);
        Process proc = pb.start();
        String stdout = new String(proc.getInputStream().readAllBytes()).trim();
        String stderr = new String(proc.getErrorStream().readAllBytes()).trim();
        int exit = proc.waitFor();
        assertEquals(0, exit,
            "Python parser exited with code " + exit + " for " + fixturePath
            + "\nstderr: " + stderr);

        Map<String, String> result = new LinkedHashMap<>();
        for (String line : stdout.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            // Key may contain a second colon (e.g. OUTPUTS:FASTQC)
            String key = line.substring(0, line.indexOf(':', colon + 1) < 0
                                            ? colon
                                            : line.indexOf(':', colon + 1) == colon ? colon
                                              : line.length()).trim();
            // Re-split properly: everything before the first colon is the prefix, the rest is the value
            String[] parts = line.split(":", 2);
            String fullKey = parts[0];
            String value = parts.length > 1 ? parts[1] : "";
            result.put(fullKey, value);
        }
        // Re-parse with correct multi-colon handling
        result.clear();
        for (String line : stdout.split("\n")) {
            if (line.isEmpty()) continue;
            // OUTPUTS:PROC_NAME:val1,val2  → key = "OUTPUTS:PROC_NAME", val = "val1,val2"
            // PROCESSES:val1,val2          → key = "PROCESSES",          val = "val1,val2"
            int firstColon = line.indexOf(':');
            if (firstColon < 0) continue;
            String prefix = line.substring(0, firstColon);
            String rest = line.substring(firstColon + 1);
            if ("OUTPUTS".equals(prefix)) {
                int secondColon = rest.indexOf(':');
                if (secondColon >= 0) {
                    String procName = rest.substring(0, secondColon);
                    String vals = rest.substring(secondColon + 1);
                    result.put("OUTPUTS:" + procName, normalise(vals));
                }
            } else {
                result.put(prefix, normalise(rest));
            }
        }
        return result;
    }

    /**
     * Convert a {@link ParsedPipeline} to the same line-based report format used by
     * the Python script, so that fields can be compared directly.
     */
    private Map<String, String> toReport(ParsedPipeline p) {
        Map<String, String> m = new LinkedHashMap<>();

        m.put("PROCESSES", normalise(
            p.getProcesses().stream().map(NfProcess::getName).sorted().collect(Collectors.joining(","))));

        m.put("WORKFLOWS", normalise(
            p.getWorkflows().stream().map(w -> String.valueOf(w.getName())).sorted()
             .collect(Collectors.joining(","))));

        m.put("INCLUDES", normalise(
            p.getIncludes().stream().map(NfInclude::getPath).sorted()
             .collect(Collectors.joining(","))));

        m.put("CONNECTIONS", normalise(
            p.getConnections().stream()
             .map(c -> c[0] + "->" + c[1]).sorted()
             .collect(Collectors.joining(","))));

        for (NfProcess proc : p.getProcesses()) {
            if (!proc.getOutputs().isEmpty()) {
                String vals = proc.getOutputs().stream().sorted().collect(Collectors.joining(","));
                m.put("OUTPUTS:" + proc.getName(), normalise(vals));
            }
        }
        return m;
    }

    /** Sort comma-separated tokens so comparison is order-independent. Normalises Python's
     *  {@code "None"} and Java's {@code "null"} to the same sentinel {@code "<entry>"}
     *  representing an unnamed (entry) workflow. */
    private String normalise(String csv) {
        if (csv == null || csv.isEmpty()) return "";
        String[] tokens = csv.split(",");
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i].trim();
            // Python str(None)="None", Java String.valueOf(null)="null" → same thing
            if ("None".equals(t) || "null".equals(t)) tokens[i] = "<entry>";
            else tokens[i] = t;
        }
        Arrays.sort(tokens);
        return String.join(",", tokens);
    }

    private void assertSameField(String field,
                                  Map<String, String> pyResult,
                                  Map<String, String> javaResult,
                                  String fixture) {
        String pyVal   = pyResult.getOrDefault(field, "");
        String javaVal = javaResult.getOrDefault(field, "");
        assertEquals(pyVal, javaVal,
            "[" + fixture + "] Field '" + field + "' differs:\n"
            + "  Python → " + (pyVal.isEmpty() ? "(empty)" : pyVal) + "\n"
            + "  Java   → " + (javaVal.isEmpty() ? "(empty)" : javaVal));
    }
}
