package com.nfmapper.parser;

import com.nfmapper.model.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    private static final NextflowParser PARSER = new NextflowParser();
    private static final String FIXTURES_DIR;

    static {
        // Look for fixtures in the test classpath (src/test/resources/fixtures)
        java.net.URL res = ParserTest.class.getResource("/fixtures");
        if (res != null) {
            FIXTURES_DIR = res.getPath();
        } else {
            // Fallback: relative path for IDE runs
            FIXTURES_DIR = Paths.get(System.getProperty("user.dir"), "..", "tests", "fixtures").normalize().toString();
        }
    }

    private static String fixture(String name) {
        return FIXTURES_DIR + File.separator + name;
    }

    // -------------------------------------------------------------------------
    // Basic content tests
    // -------------------------------------------------------------------------

    @Test void testReturnsEmptyPipelineForEmptyContent() {
        ParsedPipeline p = PARSER.parseContent("");
        assertTrue(p.getProcesses().isEmpty());
        assertTrue(p.getWorkflows().isEmpty());
        assertTrue(p.getIncludes().isEmpty());
        assertTrue(p.getConnections().isEmpty());
    }

    @Test void testExtractsSingleProcess() {
        ParsedPipeline p = PARSER.parseContent(
            "process MY_PROCESS {\n    script:\n    'echo hello'\n}\n");
        assertEquals(1, p.getProcesses().size());
        assertEquals("MY_PROCESS", p.getProcesses().get(0).getName());
    }

    @Test void testExtractsMultipleProcesses() {
        String content =
            "process STEP_ONE {\n    script:\n    'echo one'\n}\n" +
            "process STEP_TWO {\n    script:\n    'echo two'\n}\n";
        ParsedPipeline p = PARSER.parseContent(content);
        List<String> names = p.getProcesses().stream().map(NfProcess::getName).toList();
        assertTrue(names.contains("STEP_ONE"));
        assertTrue(names.contains("STEP_TWO"));
    }

    @Test void testExtractsContainer() {
        String content =
            "process CONTAINERIZED {\n" +
            "    container 'ubuntu:22.04'\n" +
            "    script:\n" +
            "    'echo hi'\n" +
            "}\n";
        ParsedPipeline p = PARSER.parseContent(content);
        assertFalse(p.getProcesses().isEmpty());
        assertTrue(p.getProcesses().get(0).getContainers().contains("ubuntu:22.04"));
    }

    @Test void testExtractsConda() {
        String content =
            "process CONDA_PROC {\n" +
            "    conda 'bioconda::samtools=1.15'\n" +
            "    script:\n" +
            "    'samtools --version'\n" +
            "}\n";
        ParsedPipeline p = PARSER.parseContent(content);
        assertFalse(p.getProcesses().isEmpty());
        assertTrue(p.getProcesses().get(0).getCondas().stream().anyMatch(c -> c.contains("samtools")));
    }

    @Test void testUnnamedWorkflowConnection() {
        String content =
            "process A {\n    script:\n    'echo a'\n}\n" +
            "process B {\n    script:\n    'echo b'\n}\n" +
            "workflow {\n" +
            "    A(params.input)\n" +
            "    B(A.out.result)\n" +
            "}\n";
        ParsedPipeline p = PARSER.parseContent(content);
        assertTrue(containsConnection(p.getConnections(), "A", "B"));
    }

    @Test void testNamedWorkflowConnection() {
        String content =
            "process ALIGN {\n    script:\n    'bwa mem'\n}\n" +
            "process SORT {\n    script:\n    'samtools sort'\n}\n" +
            "workflow MYFLOW {\n" +
            "    take: reads\n" +
            "    main:\n" +
            "        ALIGN(reads)\n" +
            "        SORT(ALIGN.out.bam)\n" +
            "}\n";
        ParsedPipeline p = PARSER.parseContent(content);
        assertTrue(containsConnection(p.getConnections(), "ALIGN", "SORT"));
    }

    @Test void testExtractsOutputChannelPatterns() {
        String content = """
            process ALIGN {
                output:
                    tuple val(meta), path("*.bam"), emit: bam
                script: 'echo hi'
            }
            """;
        ParsedPipeline p = PARSER.parseContent(content);
        assertEquals(1, p.getProcesses().size());
        assertTrue(p.getProcesses().get(0).getOutputs().contains("*.bam"),
            "Expected *.bam in outputs, got: " + p.getProcesses().get(0).getOutputs());
    }

    @Test void testExtractsInputChannelPatterns() {
        String content = """
            process PROC {
                input:
                    path "*.fastq.gz"
                script: 'echo hi'
            }
            """;
        ParsedPipeline p = PARSER.parseContent(content);
        assertFalse(p.getProcesses().isEmpty());
        assertTrue(p.getProcesses().get(0).getInputs().contains("*.fastq.gz"));
    }

    @Test void testSkipsScriptSectionPaths() {
        String content = """
            process PROC {
                output:
                    path "*.bam", emit: bam
                script:
                '''
                path("some/fake/path.bam")
                '''
            }
            """;
        ParsedPipeline p = PARSER.parseContent(content);
        assertFalse(p.getProcesses().isEmpty());
        assertEquals(List.of("*.bam"), p.getProcesses().get(0).getOutputs());
    }

    @Test void testVariablePathNotCaptured() {
        String content = """
            process PROC {
                input:
                    path reads
                output:
                    path outfile
                script: 'echo hi'
            }
            """;
        ParsedPipeline p = PARSER.parseContent(content);
        assertFalse(p.getProcesses().isEmpty());
        NfProcess proc = p.getProcesses().get(0);
        assertTrue(proc.getInputs().isEmpty(), "inputs should be empty, got: " + proc.getInputs());
        assertTrue(proc.getOutputs().isEmpty(), "outputs should be empty, got: " + proc.getOutputs());
    }

    @Test void testExtractsIncludes() {
        String content = "include { FASTQC } from './modules/fastqc'";
        ParsedPipeline p = PARSER.parseContent(content);
        assertFalse(p.getIncludes().isEmpty());
        assertEquals("./modules/fastqc", p.getIncludes().get(0).getPath());
    }

    @Test void testIncludeImportsExtracted() {
        String content = "include { FASTQC; MULTIQC } from './modules'";
        ParsedPipeline p = PARSER.parseContent(content);
        assertFalse(p.getIncludes().isEmpty());
        List<String> imports = p.getIncludes().get(0).getImports();
        assertTrue(imports.contains("FASTQC"));
        assertTrue(imports.contains("MULTIQC"));
    }

    @Test void testFileNotFound() {
        assertThrows(IOException.class, () -> PARSER.parseFile("/nonexistent/path/pipeline.nf"));
    }

    // -------------------------------------------------------------------------
    // Fixture tests
    // -------------------------------------------------------------------------

    @Test void testMinimalProcessFixture() throws IOException {
        ParsedPipeline p = PARSER.parseFile(fixture("minimal_process.nf"));
        assertEquals(1, p.getProcesses().size());
        assertEquals("HELLO", p.getProcesses().get(0).getName());
        assertTrue(p.getWorkflows().isEmpty());
        assertTrue(p.getIncludes().isEmpty());
    }

    @Test void testSimpleWorkflowFixture() throws IOException {
        ParsedPipeline p = PARSER.parseFile(fixture("simple_workflow.nf"));
        List<String> names = p.getProcesses().stream().map(NfProcess::getName).toList();
        assertTrue(names.contains("FASTQC"), "Expected FASTQC in " + names);
        assertTrue(names.contains("MULTIQC"), "Expected MULTIQC in " + names);
        assertTrue(containsConnection(p.getConnections(), "FASTQC", "MULTIQC"),
            "Expected FASTQC->MULTIQC connection, got: " + Arrays.deepToString(p.getConnections().toArray()));
    }

    @Test void testSimpleWorkflowContainer() throws IOException {
        ParsedPipeline p = PARSER.parseFile(fixture("simple_workflow.nf"));
        NfProcess fastqc = p.getProcesses().stream().filter(pr -> "FASTQC".equals(pr.getName())).findFirst().orElseThrow();
        assertFalse(fastqc.getContainers().isEmpty());
        assertTrue(fastqc.getContainers().stream().anyMatch(c -> c.toLowerCase().contains("fastqc")));
    }

    @Test void testSimpleWorkflowOutputs() throws IOException {
        ParsedPipeline p = PARSER.parseFile(fixture("simple_workflow.nf"));
        NfProcess fastqc = p.getProcesses().stream().filter(pr -> "FASTQC".equals(pr.getName())).findFirst().orElseThrow();
        assertTrue(fastqc.getOutputs().contains("*.html"), "Expected *.html in " + fastqc.getOutputs());
        assertTrue(fastqc.getOutputs().contains("*.zip"), "Expected *.zip in " + fastqc.getOutputs());
    }

    @Test void testComplexWorkflowFixture() throws IOException {
        ParsedPipeline p = PARSER.parseFile(fixture("complex_workflow.nf"));
        List<String> procNames = p.getProcesses().stream().map(NfProcess::getName).toList();
        assertTrue(procNames.contains("STAR_ALIGN"), "Expected STAR_ALIGN in " + procNames);
        assertTrue(procNames.contains("SAMTOOLS_SORT"), "Expected SAMTOOLS_SORT in " + procNames);
        assertTrue(procNames.contains("FEATURECOUNTS"), "Expected FEATURECOUNTS in " + procNames);
        assertTrue(containsConnection(p.getConnections(), "STAR_ALIGN", "SAMTOOLS_SORT"),
            "Expected STAR_ALIGN->SAMTOOLS_SORT, got: " + connectionList(p.getConnections()));
    }

    @Test void testNfCoreFastqcModule() throws IOException {
        ParsedPipeline p = PARSER.parseFile(fixture("nf_core_fastqc_module.nf"));
        assertFalse(p.getProcesses().isEmpty());
        NfProcess fastqc = p.getProcesses().get(0);
        assertEquals("FASTQC", fastqc.getName());
        assertTrue(fastqc.getOutputs().contains("*.html"), "Expected *.html in " + fastqc.getOutputs());
        assertTrue(fastqc.getOutputs().contains("*.zip"), "Expected *.zip in " + fastqc.getOutputs());
    }

    @Test void testNfCoreFetchngsSra() throws IOException {
        ParsedPipeline p = PARSER.parseFile(fixture("nf_core_fetchngs_sra.nf"));
        assertFalse(p.getIncludes().isEmpty());
        // Should have the SRA workflow
        assertFalse(p.getWorkflows().isEmpty());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean containsConnection(List<String[]> conns, String src, String dst) {
        return conns.stream().anyMatch(c -> src.equals(c[0]) && dst.equals(c[1]));
    }

    private String connectionList(List<String[]> conns) {
        return conns.stream().map(c -> c[0] + "->" + c[1])
                    .reduce((a, b) -> a + ", " + b).orElse("(empty)");
    }
}
