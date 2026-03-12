package com.nfmapper.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

class CliTest {

    private static final String FIXTURES_DIR;

    static {
        java.net.URL res = CliTest.class.getResource("/fixtures");
        if (res != null) {
            FIXTURES_DIR = res.getPath();
        } else {
            FIXTURES_DIR = Paths.get(System.getProperty("user.dir"), "..", "tests", "fixtures").normalize().toString();
        }
    }

    private static String fixture(String name) {
        return FIXTURES_DIR + File.separator + name;
    }

    private String runCli(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuf);
        NfMapperCli.run(args, out, System.err);
        return outBuf.toString();
    }

    private int runCliReturnCode(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuf);
        PrintStream err = new PrintStream(errBuf);
        return NfMapperCli.run(args, out, err);
    }

    @Test
    void testRunsSuccessfully() {
        int rc = runCliReturnCode(fixture("minimal_process.nf"));
        assertEquals(0, rc);
    }

    @Test
    void testStdoutContainsGitGraph() {
        String output = runCli(fixture("minimal_process.nf"));
        assertTrue(output.contains("gitGraph LR:"), "Output was:\n" + output);
        assertTrue(output.contains("commit id: \"HELLO\""), "Output was:\n" + output);
    }

    @Test
    void testTitleFlag() {
        String output = runCli(fixture("minimal_process.nf"), "--title", "My Pipeline");
        assertTrue(output.contains("title: My Pipeline"), "Output was:\n" + output);
    }

    @Test
    void testFormatMd() {
        String output = runCli(fixture("minimal_process.nf"), "--format", "md");
        assertTrue(output.contains("```mermaid"), "Output was:\n" + output);
        assertTrue(output.contains("```"), "Output was:\n" + output);
    }

    @Test
    void testOutputFile() throws IOException {
        Path tmpFile = Files.createTempFile("nf-mapper-test-", ".md");
        try {
            int rc = runCliReturnCode(fixture("minimal_process.nf"), "-o", tmpFile.toString());
            assertEquals(0, rc);
            String content = Files.readString(tmpFile);
            assertTrue(content.contains("gitGraph LR:"));
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test
    void testMissingInputFileError() {
        int rc = runCliReturnCode("/nonexistent/path/pipeline.nf");
        assertNotEquals(0, rc);
    }

    @Test
    void testSimpleWorkflowConnections() {
        String output = runCli(fixture("simple_workflow.nf"));
        assertTrue(output.contains("commit id: \"FASTQC\""), "Output was:\n" + output);
        assertTrue(output.contains("commit id: \"MULTIQC\""), "Output was:\n" + output);
    }

    @Test
    void testConditionalRendererMode() {
        String output = runCli(fixture("if_workflow.nf"), "--renderer", "conditional");
        assertTrue(output.contains("commit id: \"if: params.run_qc\" type: REVERSE"),
                "Output was:\n" + output);
        assertTrue(output.contains("branch if_params_run_qc"),
                "Output was:\n" + output);
    }

    @Test
    void testMetroRendererMode() {
        String output = runCli(fixture("complex_workflow.nf"), "--renderer", "metro");
        assertTrue(output.contains("gitGraph LR:"), "Output was:\n" + output);
        assertTrue(output.contains("commit id: \"STAR_ALIGN\""), "Output was:\n" + output);
        assertFalse(output.contains("flowchart LR"), "Output was:\n" + output);
    }

    @Test
    void testInvalidRendererModeReturnsError() {
        int rc = runCliReturnCode(fixture("minimal_process.nf"), "--renderer", "nope");
        assertNotEquals(0, rc);
    }

    @Test
    void testExplicitTransformerRendererAndThemeSelection() {
        String output = runCli(
                fixture("minimal_process.nf"),
                "--renderer", "metro-map",
                "--theme", "plain");
        assertTrue(output.contains("gitGraph LR:"), "Output was:\n" + output);
        assertTrue(output.contains("'theme': 'default'"), "Output was:\n" + output);
    }

    @Test
    void testCliSelectionMatrixSmoke() {
        record Case(String name, String fixtureName, String renderer, String theme,
                String expectedTheme, String expectedToken) {
        }

        List<Case> cases = List.of(
                new Case("default + gitgraph + nf-core", "minimal_process.nf", "gitgraph", "nf-core",
                        "'theme': 'base'", "commit id: \"HELLO\""),
                new Case("metro + metro-map + plain", "minimal_process.nf", "metro-map", "plain",
                        "'theme': 'default'", "commit id: \"HELLO\""),
                new Case("conditional + gitgraph + plain", "if_workflow.nf", "conditional", "plain",
                        "'theme': 'default'", "type: REVERSE"));

        for (Case testCase : cases) {
            String output = runCli(
                    fixture(testCase.fixtureName()),
                    "--renderer", testCase.renderer(),
                    "--theme", testCase.theme());
            assertTrue(output.contains("gitGraph LR:"), "Missing gitGraph for case: " + testCase.name());
            assertTrue(output.contains(testCase.expectedTheme()),
                    "Unexpected theme for case: " + testCase.name() + "\n" + output);
            assertTrue(output.contains(testCase.expectedToken()),
                    "Missing expected token for case: " + testCase.name() + "\n" + output);
        }
    }

    @Test
    void testInvalidThemeReturnsError() {
        int rc = runCliReturnCode(fixture("minimal_process.nf"), "--theme", "unknown");
        assertNotEquals(0, rc);
    }

    @Test
    void testRegenerateReadsRendererAndThemeAttrs() throws IOException {
        Path temp = Files.createTempFile("nf-mapper-regenerate-", ".md");
        try {
            String markerBlock = "<!-- nf-mapper pipeline=\"" + fixture("if_workflow.nf")
                    + "\" renderer=\"conditional\" theme=\"plain\" format=\"plain\" -->\n"
                    + "old\n"
                    + "<!-- /nf-mapper -->\n";
            Files.writeString(temp, markerBlock);
            int rc = runCliReturnCode("--regenerate", temp.toString());
            assertEquals(0, rc);
            String updated = Files.readString(temp);
            assertTrue(updated.contains("commit id: \"if: params.run_qc\" type: REVERSE"), "Output was:\n" + updated);
            assertTrue(updated.contains("'theme': 'default'"), "Output was:\n" + updated);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
