package com.nfmapper.mermaid;

import com.nfmapper.model.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MermaidRendererTest {

    private static final MermaidRenderer RENDERER = new MermaidRenderer();

    private ParsedPipeline pipeline(NfProcess... procs) {
        return pipeline(List.of(procs), Collections.emptyList());
    }

    private ParsedPipeline pipeline(List<NfProcess> procs, List<String[]> conns) {
        return new ParsedPipeline(procs, Collections.emptyList(), Collections.emptyList(), conns);
    }

    @Test void testReturnsString() {
        assertInstanceOf(String.class, RENDERER.render(pipeline()));
    }

    @Test void testStartsWithInitThenGitGraph() {
        String result = RENDERER.render(pipeline());
        String[] lines = result.split("\n");
        // Init line: starts with %%{init: ...
        assertTrue(lines[0].startsWith("%%{init: "), "Expected init line, got: " + lines[0]);
        // nf-metromap theme
        assertTrue(lines[0].contains("'theme': 'base'"), "Expected theme:base in init");
        assertTrue(lines[0].contains("'git0': '#24B064'"), "Expected nf-core green in themeVariables");
        // gitGraph section
        assertTrue(lines[0].contains("'showBranches': true"), "Expected showBranches:true in init");
        assertTrue(lines[0].contains("'parallelCommits': false"), "Expected parallelCommits:false in init");
        assertEquals("gitGraph LR:", lines[1]);
        assertEquals("   checkout main", lines[2]);
    }

    @Test void testTitleAddsFrontMatter() {
        String result = RENDERER.render(pipeline(), "My Pipeline", null);
        String[] lines = result.split("\n");
        assertEquals("---", lines[0]);
        assertEquals("title: My Pipeline", lines[1]);
        assertEquals("---", lines[2]);
        assertTrue(lines[3].startsWith("%%{init:"), "Expected init on line 3");
        assertEquals("gitGraph LR:", lines[4]);
        assertEquals("   checkout main", lines[5]);
    }

    @Test void testNoTitleNoFrontMatter() {
        String result = RENDERER.render(pipeline());
        assertFalse(result.contains("---"));
    }

    @Test void testSingleProcessOnMain() {
        String result = RENDERER.render(pipeline(new NfProcess("FASTQC")));
        assertTrue(result.contains("   commit id: \"FASTQC\""));
        assertFalse(result.contains("branch"));
    }

    @Test void testConnectionCreatesBranch() {
        // Two processes with no direct connection → flat rendering → branch named after process
        ParsedPipeline p = new ParsedPipeline(
            List.of(new NfProcess("A"), new NfProcess("B")),
            List.of(new NfWorkflow(null, List.of("A", "B"))),
            Collections.emptyList(),
            Collections.emptyList()
        );
        String result = RENDERER.render(p);
        assertTrue(result.contains("branch B"), "Expected branch named 'B', got:\n" + result);
    }

    @Test void testChannelHighlightCommits() {
        NfProcess proc = new NfProcess("ALIGN", Collections.emptyList(), Collections.emptyList(),
                                        Collections.emptyList(), Collections.emptyList(),
                                        List.of("*.bam"));
        String result = RENDERER.render(pipeline(proc));
        // Single output: tag shows full pattern name, not just extension
        assertTrue(result.contains("commit id: \"ALIGN: *.bam\" type: HIGHLIGHT tag: \"*.bam\""),
            "Result was:\n" + result);
    }

    @Test void testMultipleCherryPicksAreAggregated() {
        // ALIGN and SORT are both on the main path (ALIGN → SORT → QC → REPORT).
        // MERGE is off-main and needs outputs from both ALIGN (*.bam) and SORT (*.sorted.bam).
        // Instead of emitting two cherry-picks, they should be aggregated into one
        // with the second channel shown as an explicit tag.
        NfProcess align = new NfProcess("ALIGN", Collections.emptyList(), Collections.emptyList(),
                                         Collections.emptyList(), Collections.emptyList(), List.of("*.bam"));
        NfProcess sort = new NfProcess("SORT", Collections.emptyList(), Collections.emptyList(),
                                        Collections.emptyList(), Collections.emptyList(), List.of("*.sorted.bam"));
        NfProcess qc = new NfProcess("QC");
        NfProcess report = new NfProcess("REPORT");
        NfProcess merge = new NfProcess("MERGE");
        ParsedPipeline p = pipeline(
            List.of(align, sort, qc, report, merge),
            List.of(
                new String[]{"ALIGN", "SORT"}, new String[]{"SORT", "QC"},
                new String[]{"QC", "REPORT"},
                new String[]{"ALIGN", "MERGE"}, new String[]{"SORT", "MERGE"}
            ));
        String result = RENDERER.render(p);
        int cherryPickCount = 0;
        for (String line : result.split("\n")) {
            if (line.trim().startsWith("cherry-pick")) cherryPickCount++;
        }
        assertEquals(1, cherryPickCount,
            "Multiple sequential cherry-picks should be aggregated into one:\n" + result);
        // With 2 cherry-picks, the 2nd channel ID is shown as an explicit tag
        assertTrue(result.contains("tag: \"SORT: *.sorted.bam\""),
            "Aggregated cherry-pick should show 2nd channel as tag:\n" + result);
    }

    @Test void testCherryPickForCrossBranchChannels() {
        // ALIGN on main (with *.bam output), SORT on branch taking ALIGN.bam
        // ALIGN → QC (main chain); ALIGN → SORT (branch)
        NfProcess align = new NfProcess("ALIGN", Collections.emptyList(), Collections.emptyList(),
                                         Collections.emptyList(), Collections.emptyList(), List.of("*.bam"));
        NfProcess sort = new NfProcess("SORT", Collections.emptyList(), Collections.emptyList(),
                                        Collections.emptyList(), Collections.emptyList(), List.of("*.sorted.bam"));
        NfProcess qc = new NfProcess("QC", Collections.emptyList(), Collections.emptyList(),
                                      Collections.emptyList(), Collections.emptyList(), List.of("*.qc.txt"));
        ParsedPipeline p = pipeline(List.of(align, sort, qc),
            List.of(new String[]{"ALIGN", "QC"}, new String[]{"ALIGN", "SORT"}));
        String result = RENDERER.render(p);
        assertTrue(result.contains("cherry-pick id: \"ALIGN: *.bam\""),
            "Expected cherry-pick in:\n" + result);
    }

    @Test void testConfigOverride() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("showBranches", false);  // override the default (true) to false
        String result = RENDERER.render(pipeline(), null, config);
        assertTrue(result.contains("'showBranches': false"), "Expected showBranches:false after override");
        assertTrue(result.contains("'parallelCommits': false"), "Default parallelCommits:false should be preserved");
    }

    @Test void testNoFlowchartKeyword() {
        ParsedPipeline p = pipeline(List.of(new NfProcess("A"), new NfProcess("B")),
                                     List.<String[]>of(new String[]{"A", "B"}));
        String result = RENDERER.render(p);
        assertFalse(result.contains("flowchart"));
        assertFalse(result.contains("-->"));
    }

    @Test void testMultipleOutputExtensions() {
        NfProcess proc = new NfProcess("FASTQC", Collections.emptyList(), Collections.emptyList(),
                                        Collections.emptyList(), Collections.emptyList(),
                                        List.of("*.html", "*.zip"));
        String result = RENDERER.render(pipeline(proc));
        // 2 outputs: commit ID is "PROC: *", both patterns shown as tags
        assertTrue(result.contains("\"FASTQC: *\" type: HIGHLIGHT tag: \"*.html\" tag: \"*.zip\""),
            "Expected wildcard commit ID with both output patterns as tags:\n" + result);
        assertFalse(result.contains("\"FASTQC: *.html\" type: HIGHLIGHT"),
            "First output pattern should not appear as commit ID:\n" + result);
        assertFalse(result.contains("\"FASTQC: *.zip\" type: HIGHLIGHT"),
            "Second output should not appear as a separate HIGHLIGHT commit:\n" + result);
    }

    @Test void testBranchNamedAfterProcess() {
        // In flat rendering, the branch should be named after the process it contains
        ParsedPipeline p = new ParsedPipeline(
            List.of(new NfProcess("FASTQC"), new NfProcess("TRIMGALORE")),
            List.of(new NfWorkflow(null, List.of("FASTQC", "TRIMGALORE"))),
            Collections.emptyList(),
            Collections.emptyList()
        );
        String result = RENDERER.render(p);
        assertTrue(result.contains("branch TRIMGALORE"),
            "Expected branch named TRIMGALORE, got:\n" + result);
        assertFalse(result.contains("branch_1"),
            "Should not use generic branch_1 naming");
    }

    @Test void testDagBranchNamedAfterProcess() {
        // In DAG rendering, off-main branches should be named after their first process
        NfProcess align = new NfProcess("ALIGN");
        NfProcess sort = new NfProcess("SORT");
        NfProcess qc = new NfProcess("QC");
        // ALIGN → QC (longer chain = main), ALIGN → SORT (branch)
        ParsedPipeline p = pipeline(
            List.of(align, sort, qc),
            List.of(new String[]{"ALIGN", "QC"}, new String[]{"ALIGN", "SORT"}));
        String result = RENDERER.render(p);
        assertTrue(result.contains("branch SORT"),
            "Expected branch named SORT, got:\n" + result);
    }

    @Test void testConditionalProcessGetsIfNode() {
        // A process tagged as conditional should be preceded by an if-statement commit
        Map<String, String[]> conditionalInfo = new LinkedHashMap<>();
        conditionalInfo.put("QC", new String[]{"0", "params.run_qc"});
        ParsedPipeline p = new ParsedPipeline(
            List.of(new NfProcess("QC")),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            conditionalInfo
        );
        String result = RENDERER.render(p);
        assertTrue(result.contains("type: REVERSE"),
            "Expected if-node with type REVERSE for conditional process:\n" + result);
        // The if-node should appear before the process commit
        int ifIdx = result.indexOf("type: REVERSE");
        int procIdx = result.indexOf("commit id: \"QC\"");
        assertTrue(ifIdx < procIdx,
            "if-node should appear before the process commit");
    }

    @Test void testNfMetromapThemePresent() {
        String result = RENDERER.render(pipeline());
        assertTrue(result.contains("'theme': 'base'"), "Should have base theme");
        assertTrue(result.contains("'git0': '#24B064'"), "Should have nf-core green as git0");
        assertTrue(result.contains("'git1': '#FA7F19'"), "Should have orange as git1");
        assertTrue(result.contains("'git2': '#0570b0'"), "Should have blue as git2");
    }

    @Test void testMainBlockFileRefsShownAsHighlightCommits() {
        // Flat rendering: file refs from workflow main block appear before processes
        ParsedPipeline p = new ParsedPipeline(
            List.of(new NfProcess("FASTQC")),
            List.of(new NfWorkflow(null, List.of("FASTQC"), List.of("*.fastq.gz"))),
            Collections.emptyList(),
            Collections.emptyList()
        );
        String result = RENDERER.render(p);
        assertTrue(result.contains("commit id: \"input: *.fastq.gz\" type: HIGHLIGHT"),
            "Expected HIGHLIGHT commit for main-block file ref:\n" + result);
        // File ref should appear before the process commit
        int refIdx = result.indexOf("input: *.fastq.gz");
        int procIdx = result.indexOf("commit id: \"FASTQC\"");
        assertTrue(refIdx < procIdx,
            "File ref commit should appear before process commit");
    }

    @Test void testMainBlockFileRefsTaggedWithExtension() {
        ParsedPipeline p = new ParsedPipeline(
            List.of(new NfProcess("PROC")),
            List.of(new NfWorkflow(null, List.of("PROC"), List.of("data/*.bam"))),
            Collections.emptyList(),
            Collections.emptyList()
        );
        String result = RENDERER.render(p);
        assertTrue(result.contains("tag: \"bam\""),
            "Expected tag:bam for *.bam file ref:\n" + result);
    }

    @Test void testMainBlockFileRefsShownInDagRendering() {
        // DAG rendering: file refs also appear before the first process
        NfProcess a = new NfProcess("ALIGN");
        NfProcess b = new NfProcess("SORT");
        ParsedPipeline p = new ParsedPipeline(
            List.of(a, b),
            List.of(new NfWorkflow(null, List.of("ALIGN", "SORT"), List.of("*.fastq.gz"))),
            Collections.emptyList(),
            List.<String[]>of(new String[]{"ALIGN", "SORT"})
        );
        String result = RENDERER.render(p);
        assertTrue(result.contains("commit id: \"input: *.fastq.gz\" type: HIGHLIGHT"),
            "Expected HIGHLIGHT commit in DAG rendering:\n" + result);
        int refIdx = result.indexOf("input: *.fastq.gz");
        int procIdx = result.indexOf("commit id: \"ALIGN\"");
        assertTrue(refIdx < procIdx,
            "File ref commit should appear before first process commit in DAG mode");
    }

    @Test void testMultipleMainBlockFileRefs() {
        ParsedPipeline p = new ParsedPipeline(
            List.of(new NfProcess("PROC")),
            List.of(new NfWorkflow(null, List.of("PROC"),
                    List.of("samplesheet.csv", "*.fastq.gz"))),
            Collections.emptyList(),
            Collections.emptyList()
        );
        String result = RENDERER.render(p);
        assertTrue(result.contains("input: samplesheet.csv"),
            "Expected samplesheet.csv file ref:\n" + result);
        assertTrue(result.contains("input: *.fastq.gz"),
            "Expected *.fastq.gz file ref:\n" + result);
    }

    // -------------------------------------------------------------------------
    // Cycle / dead-loop regression tests (OOM in tracePath)
    // -------------------------------------------------------------------------

    /**
     * A self-loop edge (A → A) in the connections must not cause an infinite loop
     * or OOM in renderDag.  The renderer should complete and produce output that
     * still contains the process commit.
     */
    @Test void testSelfLoopConnectionDoesNotHang() {
        ParsedPipeline p = pipeline(
            List.of(new NfProcess("A")),
            List.<String[]>of(new String[]{"A", "A"})
        );
        // Must complete without hanging or throwing
        String result = assertTimeoutPreemptively(
            java.time.Duration.ofSeconds(5),
            () -> RENDERER.render(p),
            "render() hung on a self-loop connection"
        );
        assertTrue(result.contains("commit id: \"A\""),
            "Process A should still appear in output:\n" + result);
    }

    /**
     * A two-node cycle (A → B, B → A) must not cause an infinite loop in tracePath.
     */
    @Test void testTwoNodeCycleDoesNotHang() {
        ParsedPipeline p = pipeline(
            List.of(new NfProcess("A"), new NfProcess("B")),
            List.<String[]>of(new String[]{"A", "B"}, new String[]{"B", "A"})
        );
        String result = assertTimeoutPreemptively(
            java.time.Duration.ofSeconds(5),
            () -> RENDERER.render(p),
            "render() hung on a two-node cycle"
        );
        assertTrue(result.contains("commit id: \"A\"") || result.contains("commit id: \"B\""),
            "At least one process should appear in output:\n" + result);
    }

    /**
     * An off-chain cycle (A → B → A, both not on the main path) must not cause an
     * infinite loop in emitOffChainWithChannels.
     *
     * <p>Graph: X → Z (main chain), X → A, A → B, B → A (off-chain cycle).
     */
    @Test void testOffChainCycleDoesNotHang() {
        ParsedPipeline p = pipeline(
            List.of(new NfProcess("X"), new NfProcess("Z"),
                    new NfProcess("A"), new NfProcess("B")),
            List.<String[]>of(
                new String[]{"X", "Z"}, new String[]{"X", "A"},
                new String[]{"A", "B"}, new String[]{"B", "A"}
            )
        );
        String result = assertTimeoutPreemptively(
            java.time.Duration.ofSeconds(5),
            () -> RENDERER.render(p),
            "render() hung on an off-chain cycle"
        );
        assertTrue(result.contains("commit id: \"X\""),
            "Process X should appear in output:\n" + result);
    }
}
