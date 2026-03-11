package com.nfmapper.cli;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CliTest {

    private static final String FIXTURES_DIR =
        Paths.get(System.getProperty("user.dir"), "..", "tests", "fixtures").normalize().toString();

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

    @Test void testRunsSuccessfully() {
        int rc = runCliReturnCode(fixture("minimal_process.nf"));
        assertEquals(0, rc);
    }

    @Test void testStdoutContainsGitGraph() {
        String output = runCli(fixture("minimal_process.nf"));
        assertTrue(output.contains("gitGraph LR:"), "Output was:\n" + output);
        assertTrue(output.contains("commit id: \"HELLO\""), "Output was:\n" + output);
    }

    @Test void testTitleFlag() {
        String output = runCli(fixture("minimal_process.nf"), "--title", "My Pipeline");
        assertTrue(output.contains("title: My Pipeline"), "Output was:\n" + output);
    }

    @Test void testFormatMd() {
        String output = runCli(fixture("minimal_process.nf"), "--format", "md");
        assertTrue(output.contains("```mermaid"), "Output was:\n" + output);
        assertTrue(output.contains("```"), "Output was:\n" + output);
    }

    @Test void testOutputFile() throws IOException {
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

    @Test void testMissingInputFileError() {
        int rc = runCliReturnCode("/nonexistent/path/pipeline.nf");
        assertNotEquals(0, rc);
    }

    @Test void testSimpleWorkflowConnections() {
        String output = runCli(fixture("simple_workflow.nf"));
        assertTrue(output.contains("commit id: \"FASTQC\""), "Output was:\n" + output);
        assertTrue(output.contains("commit id: \"MULTIQC\""), "Output was:\n" + output);
    }
}
