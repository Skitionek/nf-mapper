package com.nfmapper.snapshot;

import com.nfmapper.mermaid.MermaidRenderer;
import com.nfmapper.model.*;
import com.nfmapper.parser.NextflowParser;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Snapshot tests – generate Mermaid diagrams and write them to
 * {@code snapshots/*.md} for visual validation.
 *
 * <p>Each test produces (or overwrites) a Markdown file containing a fenced
 * {@code mermaid} code block. The files are committed to the repository so they
 * can be inspected on GitHub or any Markdown renderer that supports Mermaid.
 *
 * <p>These tests <b>always write</b> the current output; they are not snapshot-
 * comparison tests. Regression detection is handled by the behavioural
 * assertions in {@code MermaidRendererTest} and {@code ParserTest}.
 */
class SnapshotTest {

    private static final NextflowParser PARSER = new NextflowParser();
    private static final MermaidRenderer RENDERER = new MermaidRenderer();

    /** Resolves to the {@code snapshots/} directory at the repo root. */
    private static final Path SNAPSHOTS_DIR;

    static {
        // user.dir is the nf-mapper/ project dir when running via mvn test
        Path projectDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        // snapshots/ lives at the repo root (one level up from nf-mapper/)
        SNAPSHOTS_DIR = projectDir.getParent().resolve("snapshots");
    }

    /** Resolves a fixture by name from the test classpath. */
    private static String fixture(String name) {
        java.net.URL res = SnapshotTest.class.getResource("/fixtures/" + name);
        if (res != null) return res.getPath();
        return SNAPSHOTS_DIR.getParent().resolve("nf-mapper/src/test/resources/fixtures/" + name).toString();
    }

    private void writeSnapshot(String name, String diagram, String source) throws IOException {
        Files.createDirectories(SNAPSHOTS_DIR);
        Path out = SNAPSHOTS_DIR.resolve(name + ".md");
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(name).append("\n\n");
        if (source != null && !source.isEmpty()) {
            sb.append("> Generated from `").append(source).append("`\n\n");
        }
        sb.append("```mermaid\n").append(diagram).append("\n```\n");
        Files.writeString(out, sb.toString());
    }

    /** Build a synthetic {@link ParsedPipeline} for scenario tests. */
    private static ParsedPipeline makePipeline(
            List<NfProcess> processes,
            List<NfWorkflow> workflows,
            List<String[]> connections) {
        return new ParsedPipeline(
                processes != null ? processes : Collections.emptyList(),
                workflows != null ? workflows : Collections.emptyList(),
                Collections.emptyList(),
                connections != null ? connections : Collections.emptyList());
    }

    // -------------------------------------------------------------------------
    // Fixture-based snapshots
    // -------------------------------------------------------------------------

    @Test
    void testSnapshotMinimalProcess() throws IOException {
        ParsedPipeline pipeline = PARSER.parseFile(fixture("minimal_process.nf"));
        String diagram = RENDERER.render(pipeline, "Minimal Process", null);
        writeSnapshot("minimal_process", diagram, "nf-mapper/src/test/resources/fixtures/minimal_process.nf");
        assertTrue(diagram.contains("gitGraph"), "should contain gitGraph");
        assertTrue(diagram.contains("commit id: \"HELLO\""), "should contain HELLO process");
    }

    @Test
    void testSnapshotSimpleWorkflow() throws IOException {
        ParsedPipeline pipeline = PARSER.parseFile(fixture("simple_workflow.nf"));
        String diagram = RENDERER.render(pipeline, "nf-core/rnaseq QC", null);
        writeSnapshot("simple_workflow", diagram, "nf-mapper/src/test/resources/fixtures/simple_workflow.nf");
        assertTrue(diagram.contains("gitGraph"));
        assertTrue(diagram.contains("commit id: \"FASTQC\""));
        assertTrue(diagram.contains("commit id: \"FASTQC: *.html\" type: HIGHLIGHT tag: \"html\""));
        assertTrue(diagram.contains("commit id: \"MULTIQC\""));
    }

    @Test
    void testSnapshotComplexWorkflow() throws IOException {
        ParsedPipeline pipeline = PARSER.parseFile(fixture("complex_workflow.nf"));
        String diagram = RENDERER.render(pipeline, "RNA-seq Pipeline", null);
        writeSnapshot("complex_workflow", diagram, "nf-mapper/src/test/resources/fixtures/complex_workflow.nf");
        assertTrue(diagram.contains("gitGraph"));
        assertTrue(diagram.contains("branch"));
        assertTrue(diagram.contains("commit id: \"STAR_ALIGN: *.bam\" type: HIGHLIGHT tag: \"bam\""));
    }

    @Test
    void testSnapshotNfCoreFastqcModule() throws IOException {
        ParsedPipeline pipeline = PARSER.parseFile(fixture("nf_core_fastqc_module.nf"));
        String diagram = RENDERER.render(pipeline, "nf-core FASTQC module", null);
        writeSnapshot("nf_core_fastqc_module", diagram, "nf-mapper/src/test/resources/fixtures/nf_core_fastqc_module.nf");
        assertTrue(diagram.contains("gitGraph"));
        assertTrue(diagram.contains("commit id: \"FASTQC\""));
        assertTrue(diagram.contains("commit id: \"FASTQC: *.html\" type: HIGHLIGHT tag: \"html\""));
        assertTrue(diagram.contains("commit id: \"FASTQC: *.zip\" type: HIGHLIGHT tag: \"zip\""));
    }

    @Test
    void testSnapshotNfCoreFetchngsSra() throws IOException {
        ParsedPipeline pipeline = PARSER.parseFile(fixture("nf_core_fetchngs_sra.nf"));
        String diagram = RENDERER.render(pipeline, "nf-core/fetchngs SRA", null);
        writeSnapshot("nf_core_fetchngs_sra", diagram, "nf-mapper/src/test/resources/fixtures/nf_core_fetchngs_sra.nf");
        assertTrue(diagram.contains("gitGraph"));
        assertTrue(diagram.contains("branch"));
        assertTrue(diagram.contains("commit id: \"SRA_IDS_TO_RUNINFO\""));
    }

    // -------------------------------------------------------------------------
    // Synthetic scenario snapshots
    // -------------------------------------------------------------------------

    @Test
    void testSnapshotChannelNodes() throws IOException {
        ParsedPipeline pipeline = makePipeline(
                List.of(
                        new NfProcess("TRIM", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(),
                                List.of("*.trimmed.fastq.gz")),
                        new NfProcess("ALIGN", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(),
                                List.of("*.bam")),
                        new NfProcess("SORT", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(),
                                List.of("*.sorted.bam"))),
                null,
                List.of(new String[]{"TRIM", "ALIGN"}, new String[]{"ALIGN", "SORT"}));
        String diagram = RENDERER.render(pipeline, "Channel Nodes Example", null);
        writeSnapshot("scenario_channel_nodes", diagram, null);
        assertTrue(diagram.contains("commit id: \"ALIGN: *.bam\" type: HIGHLIGHT tag: \"bam\""));
    }

    @Test
    void testSnapshotCherryPick() throws IOException {
        ParsedPipeline pipeline = makePipeline(
                List.of(
                        new NfProcess("ALIGN", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(),
                                List.of("*.bam")),
                        new NfProcess("SORT", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(),
                                List.of("*.sorted.bam")),
                        new NfProcess("QC", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(),
                                List.of("*.qc.txt"))),
                null,
                List.of(new String[]{"ALIGN", "SORT"}, new String[]{"ALIGN", "QC"}));
        String diagram = RENDERER.render(pipeline, "Cherry-Pick Example", null);
        writeSnapshot("scenario_cherry_pick", diagram, null);
        assertTrue(diagram.contains("cherry-pick id: \"ALIGN: *.bam\""));
    }

    @Test
    void testSnapshotWorkflowCallBranches() throws IOException {
        ParsedPipeline pipeline = makePipeline(
                List.of(
                        new NfProcess("FASTQC", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(),
                                List.of("*.html", "*.zip")),
                        new NfProcess("TRIMGALORE", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(),
                                List.of("*.trimmed.fastq.gz")),
                        new NfProcess("MULTIQC", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(),
                                List.of("multiqc_report.html"))),
                List.of(new NfWorkflow("QC_WF", List.of("FASTQC", "TRIMGALORE", "MULTIQC"))),
                null);
        String diagram = RENDERER.render(pipeline, "Workflow Call Branches", null);
        writeSnapshot("scenario_workflow_call_branches", diagram, null);
        assertTrue(diagram.contains("branch"));
    }

    @Test
    void testSnapshotMerge() throws IOException {
        // Longest path (ALIGN → QC → COUNT) becomes main.
        // SORT branches off ALIGN and merges back at COUNT.
        ParsedPipeline pipeline = makePipeline(
                List.of(
                        new NfProcess("ALIGN", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(),
                                List.of("*.bam")),
                        new NfProcess("QC"),
                        new NfProcess("SORT", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(),
                                List.of("*.sorted.bam")),
                        new NfProcess("COUNT", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(),
                                List.of("*.counts.txt"))),
                null,
                List.of(
                        new String[]{"ALIGN", "QC"}, new String[]{"ALIGN", "SORT"},
                        new String[]{"QC", "COUNT"}, new String[]{"SORT", "COUNT"}));
        String diagram = RENDERER.render(pipeline, "Branch and Merge", null);
        writeSnapshot("scenario_merge", diagram, null);
        assertTrue(diagram.contains("merge "));
        assertTrue(diagram.contains("branch "));
        assertTrue(diagram.contains("cherry-pick id: \"ALIGN: *.bam\""));
        assertTrue(diagram.indexOf("merge ") < diagram.indexOf("commit id: \"COUNT: *.counts.txt\""));
    }
}
