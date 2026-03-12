package com.nfmapper.mermaid;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

import com.nfmapper.model.NfProcess;
import com.nfmapper.model.NfWorkflow;
import com.nfmapper.model.ParsedPipeline;

class MermaidRendererTest {

        protected MermaidRenderer renderer() {
                return new MermaidRenderer();
        }

        private final MermaidRenderer RENDERER = renderer();

        protected boolean expectConditionalBranchNameInDagConditionalTest() {
                return false;
        }

        protected boolean expectFlatWorkflowBranches() {
                return true;
        }

        private ParsedPipeline pipeline(NfProcess... procs) {
                return pipeline(List.of(procs), Collections.emptyList());
        }

        private ParsedPipeline pipeline(List<NfProcess> procs, List<String[]> conns) {
                return new ParsedPipeline(procs, Collections.emptyList(), Collections.emptyList(), conns);
        }

        @Test
        void testReturnsString() {
                assertInstanceOf(String.class, RENDERER.render(pipeline()));
        }

        @Test
        void testStartsWithInitThenGitGraph() {
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

        @Test
        void testTitleAddsFrontMatter() {
                String result = RENDERER.render(pipeline(), "My Pipeline", null);
                String[] lines = result.split("\n");
                assertEquals("---", lines[0]);
                assertEquals("title: My Pipeline", lines[1]);
                assertEquals("---", lines[2]);
                assertTrue(lines[3].startsWith("%%{init:"), "Expected init on line 3");
                assertEquals("gitGraph LR:", lines[4]);
                assertEquals("   checkout main", lines[5]);
        }

        @Test
        void testNoTitleNoFrontMatter() {
                String result = RENDERER.render(pipeline());
                assertFalse(result.contains("---"));
        }

        @Test
        void testSingleProcessOnMain() {
                String result = RENDERER.render(pipeline(new NfProcess("FASTQC")));
                assertTrue(result.contains("   commit id: \"FASTQC\""));
                assertFalse(result.contains("branch"));
        }

        @Test
        void testConnectionCreatesBranch() {
                // Two processes with no direct connection → flat rendering → branch named after
                // process
                ParsedPipeline p = new ParsedPipeline(
                                List.of(new NfProcess("A"), new NfProcess("B")),
                                List.of(new NfWorkflow(null, List.of("A", "B"))),
                                Collections.emptyList(),
                                Collections.emptyList());
                String result = RENDERER.render(p);
                if (expectFlatWorkflowBranches()) {
                        assertTrue(result.contains("branch B"), "Expected branch named 'B', got:\n" + result);
                } else {
                        assertFalse(result.contains("branch "), "Did not expect branch in flat rendering:\n" + result);
                }
        }

        @Test
        void testChannelHighlightCommits() {
                NfProcess proc = new NfProcess("ALIGN", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(),
                                List.of("*.bam"));
                String result = RENDERER.render(pipeline(proc));
                // Issue 4: output tag is inline on the process commit – no separate HIGHLIGHT
                // commit.
                assertTrue(result.contains("commit id: \"ALIGN\" tag: \"*.bam\""),
                                "Result was:\n" + result);
                assertFalse(result.contains("ALIGN: *.bam\" type: HIGHLIGHT"),
                                "Should not emit separate HIGHLIGHT commit for outputs:\n" + result);
        }

        @Test
        void testMultipleCherryPicksAreAggregated() {
                // ALIGN and SORT are both on the main path (ALIGN → SORT → QC → REPORT).
                // MERGE is off-main and needs outputs from both ALIGN (*.bam) and SORT
                // (*.sorted.bam).
                // Instead of emitting two cherry-picks, they should be aggregated into one
                // with the second process shown as an explicit tag (issue 3: process names).
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
                                                new String[] { "ALIGN", "SORT" }, new String[] { "SORT", "QC" },
                                                new String[] { "QC", "REPORT" },
                                                new String[] { "ALIGN", "MERGE" }, new String[] { "SORT", "MERGE" }));
                String result = RENDERER.render(p);
                int cherryPickCount = 0;
                for (String line : result.split("\n")) {
                        if (line.trim().startsWith("cherry-pick"))
                                cherryPickCount++;
                }
                assertEquals(1, cherryPickCount,
                                "Multiple sequential cherry-picks should be aggregated into one:\n" + result);
                // With 2 predecessors on different branch, 2nd process name shown as an
                // explicit tag
                assertTrue(result.contains("tag: \"SORT\""),
                                "Aggregated cherry-pick should show 2nd predecessor name as tag:\n" + result);
        }

        @Test
        void testCherryPickForCrossBranchChannels() {
                // ALIGN on main (with *.bam output), SORT on branch taking ALIGN.bam
                // ALIGN → QC (main chain); ALIGN → SORT (branch)
                NfProcess align = new NfProcess("ALIGN", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(), List.of("*.bam"));
                NfProcess sort = new NfProcess("SORT", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(), List.of("*.sorted.bam"));
                NfProcess qc = new NfProcess("QC", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(), List.of("*.qc.txt"));
                ParsedPipeline p = pipeline(List.of(align, sort, qc),
                                List.of(new String[] { "ALIGN", "QC" }, new String[] { "ALIGN", "SORT" }));
                String result = RENDERER.render(p);
                // Issue 3: cherry-pick references the process name, not a channel ID
                assertTrue(result.contains("cherry-pick id: \"ALIGN\""),
                                "Expected cherry-pick referencing process name in:\n" + result);
        }

        @Test
        void testCherryPickDoesNotReferenceNonEmittedMergeTarget() {
                // Main path: A -> B -> C -> D -> E
                // Off-branch: A -> X -> D (merges back into D)
                // Additional branch from D: D -> G
                //
                // When A->X->D is merged, D is represented by the merge event and does not get
                // a
                // standalone "commit id: \"D\"" line. A later branch starting at D must not
                // emit
                // "cherry-pick id: \"D\"" because that commit ID does not exist in the graph.
                NfProcess a = new NfProcess("A", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(), List.of("a.out"));
                NfProcess b = new NfProcess("B");
                NfProcess c = new NfProcess("C");
                NfProcess d = new NfProcess("D", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(), List.of("d.out"));
                NfProcess e = new NfProcess("E");
                NfProcess x = new NfProcess("X");
                NfProcess g = new NfProcess("G", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), List.of("d.out"), Collections.emptyList());

                ParsedPipeline p = pipeline(
                                List.of(a, b, c, d, e, x, g),
                                List.of(
                                                new String[] { "A", "B" },
                                                new String[] { "B", "C" },
                                                new String[] { "C", "D" },
                                                new String[] { "D", "E" },
                                                new String[] { "A", "X" },
                                                new String[] { "X", "D" },
                                                new String[] { "D", "G" }));

                String result = RENDERER.render(p);
                assertFalse(result.contains("cherry-pick id: \"D\""),
                                "Cherry-pick must not reference non-emitted merge-target commit D:\n" + result);
        }

        @Test
        void testConfigOverride() {
                Map<String, Object> config = new LinkedHashMap<>();
                config.put("showBranches", false); // override the default (true) to false
                String result = RENDERER.render(pipeline(), null, config);
                assertTrue(result.contains("'showBranches': false"), "Expected showBranches:false after override");
                assertTrue(result.contains("'parallelCommits': false"),
                                "Default parallelCommits:false should be preserved");
        }

        @Test
        void testNoFlowchartKeyword() {
                ParsedPipeline p = pipeline(List.of(new NfProcess("A"), new NfProcess("B")),
                                List.<String[]>of(new String[] { "A", "B" }));
                String result = RENDERER.render(p);
                assertFalse(result.contains("flowchart"));
                assertFalse(result.contains("-->"));
        }

        @Test
        void testMultipleOutputExtensions() {
                NfProcess proc = new NfProcess("FASTQC", Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), Collections.emptyList(),
                                List.of("*.html", "*.zip"));
                String result = RENDERER.render(pipeline(proc));
                // Issue 4: output tags inline on the process commit – no separate HIGHLIGHT
                // commit.
                assertTrue(result.contains("commit id: \"FASTQC\" tag: \"*.html\" tag: \"*.zip\""),
                                "Expected process commit with both output patterns as inline tags:\n" + result);
                assertFalse(result.contains("type: HIGHLIGHT"),
                                "Should not emit any HIGHLIGHT commit when outputs are inlined:\n" + result);
        }

        @Test
        void testBranchNamedAfterProcess() {
                // In flat rendering, the branch should be named after the process it contains
                ParsedPipeline p = new ParsedPipeline(
                                List.of(new NfProcess("FASTQC"), new NfProcess("TRIMGALORE")),
                                List.of(new NfWorkflow(null, List.of("FASTQC", "TRIMGALORE"))),
                                Collections.emptyList(),
                                Collections.emptyList());
                String result = RENDERER.render(p);
                if (expectFlatWorkflowBranches()) {
                        assertTrue(result.contains("branch TRIMGALORE"),
                                        "Expected branch named TRIMGALORE, got:\n" + result);
                        assertFalse(result.contains("branch_1"),
                                        "Should not use generic branch_1 naming");
                } else {
                        assertFalse(result.contains("branch "), "Did not expect branch in flat rendering:\n" + result);
                }
        }

        @Test
        void testDagBranchNamedAfterProcess() {
                // In DAG rendering, off-main branches should be named after their first process
                NfProcess align = new NfProcess("ALIGN");
                NfProcess sort = new NfProcess("SORT");
                NfProcess qc = new NfProcess("QC");
                // ALIGN → QC (longer chain = main), ALIGN → SORT (branch)
                ParsedPipeline p = pipeline(
                                List.of(align, sort, qc),
                                List.of(new String[] { "ALIGN", "QC" }, new String[] { "ALIGN", "SORT" }));
                String result = RENDERER.render(p);
                assertTrue(result.contains("branch SORT"),
                                "Expected branch named SORT, got:\n" + result);
        }

        @Test
        void testConditionalProcessGetsIfNode() {
                // A process tagged as conditional should be preceded by an if-statement commit
                Map<String, String[]> conditionalInfo = new LinkedHashMap<>();
                conditionalInfo.put("QC", new String[] { "0", "params.run_qc" });
                ParsedPipeline p = new ParsedPipeline(
                                List.of(new NfProcess("QC")),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                conditionalInfo);
                String result = RENDERER.render(p);
                assertTrue(result.contains("type: REVERSE"),
                                "Expected if-node with type REVERSE for conditional process:\n" + result);
                // The if-node should appear before the process commit
                int ifIdx = result.indexOf("type: REVERSE");
                int procIdx = result.indexOf("commit id: \"QC\"");
                assertTrue(ifIdx < procIdx,
                                "if-node should appear before the process commit");
        }

        @Test
        void testConditionalProcessUsesConditionTextAsNodeName() {
                // The REVERSE node uses "if: conditionText" format – not bare text, not "if:
                // PROCNAME"
                Map<String, String[]> conditionalInfo = new LinkedHashMap<>();
                conditionalInfo.put("QC", new String[] { "0", "params.run_qc" });
                ParsedPipeline p = new ParsedPipeline(
                                List.of(new NfProcess("QC")),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                conditionalInfo);
                String result = RENDERER.render(p);
                assertTrue(result.contains("commit id: \"if: params.run_qc\" type: REVERSE"),
                                "Expected 'if: conditionText' as REVERSE node id:\n" + result);
                assertFalse(result.contains("commit id: \"if: QC\""),
                                "Should not use 'if: PROCNAME' format:\n" + result);
        }

        @Test
        void testConditionalBranchDeclaredAfterIfNode() {
                // In DAG rendering, the "if:" REVERSE node must appear on the parent branch
                // BEFORE
                // the branch declaration for the conditional process.
                NfProcess align = new NfProcess("ALIGN");
                NfProcess qc = new NfProcess("QC");
                NfProcess count = new NfProcess("COUNT");
                Map<String, String[]> conditionalInfo = new LinkedHashMap<>();
                conditionalInfo.put("QC", new String[] { "0", "params.run_qc" });
                ParsedPipeline p = new ParsedPipeline(
                                List.of(align, qc, count),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                List.<String[]>of(new String[] { "ALIGN", "QC" }, new String[] { "ALIGN", "COUNT" }),
                                conditionalInfo);
                String result = RENDERER.render(p);
                // "if:" REVERSE should appear before the "branch QC" declaration
                int ifIdx = result.indexOf("commit id: \"if: params.run_qc\" type: REVERSE");
                String expectedBranch = expectConditionalBranchNameInDagConditionalTest() ? "branch if_params_run_qc"
                                : "branch QC";
                int branchIdx = result.indexOf(expectedBranch);
                assertTrue(ifIdx >= 0,
                                "Expected 'if: params.run_qc' REVERSE node:\n" + result);
                assertTrue(branchIdx >= 0,
                                "Expected '" + expectedBranch + "':\n" + result);
                assertTrue(ifIdx < branchIdx,
                                "if-node must appear before the branch declaration:\n" + result);
        }

        @Test
        void testNfMetromapThemePresent() {
                String result = RENDERER.render(pipeline());
                assertTrue(result.contains("'theme': 'base'"), "Should have base theme");
                assertTrue(result.contains("'git0': '#24B064'"), "Should have nf-core green as git0");
                assertTrue(result.contains("'git1': '#FA7F19'"), "Should have orange as git1");
                assertTrue(result.contains("'git2': '#0570b0'"), "Should have blue as git2");
        }

        @Test
        void testMainBlockFileRefsShownAsHighlightCommits() {
                // Flat rendering: file refs from workflow main block appear before processes
                ParsedPipeline p = new ParsedPipeline(
                                List.of(new NfProcess("FASTQC")),
                                List.of(new NfWorkflow(null, List.of("FASTQC"), List.of("*.fastq.gz"))),
                                Collections.emptyList(),
                                Collections.emptyList());
                String result = RENDERER.render(p);
                assertTrue(result.contains("commit id: \"input: *.fastq.gz\" type: HIGHLIGHT"),
                                "Expected HIGHLIGHT commit for main-block file ref:\n" + result);
                // File ref should appear before the process commit
                int refIdx = result.indexOf("input: *.fastq.gz");
                int procIdx = result.indexOf("commit id: \"FASTQC\"");
                assertTrue(refIdx < procIdx,
                                "File ref commit should appear before process commit");
        }

        @Test
        void testMainBlockFileRefsTaggedWithExtension() {
                ParsedPipeline p = new ParsedPipeline(
                                List.of(new NfProcess("PROC")),
                                List.of(new NfWorkflow(null, List.of("PROC"), List.of("data/*.bam"))),
                                Collections.emptyList(),
                                Collections.emptyList());
                String result = RENDERER.render(p);
                assertTrue(result.contains("tag: \"bam\""),
                                "Expected tag:bam for *.bam file ref:\n" + result);
        }

        @Test
        void testMainBlockFileRefsShownInDagRendering() {
                // DAG rendering: file refs also appear before the first process
                NfProcess a = new NfProcess("ALIGN");
                NfProcess b = new NfProcess("SORT");
                ParsedPipeline p = new ParsedPipeline(
                                List.of(a, b),
                                List.of(new NfWorkflow(null, List.of("ALIGN", "SORT"), List.of("*.fastq.gz"))),
                                Collections.emptyList(),
                                List.<String[]>of(new String[] { "ALIGN", "SORT" }));
                String result = RENDERER.render(p);
                assertTrue(result.contains("commit id: \"input: *.fastq.gz\" type: HIGHLIGHT"),
                                "Expected HIGHLIGHT commit in DAG rendering:\n" + result);
                int refIdx = result.indexOf("input: *.fastq.gz");
                int procIdx = result.indexOf("commit id: \"ALIGN\"");
                assertTrue(refIdx < procIdx,
                                "File ref commit should appear before first process commit in DAG mode");
        }

        @Test
        void testMultipleMainBlockFileRefs() {
                ParsedPipeline p = new ParsedPipeline(
                                List.of(new NfProcess("PROC")),
                                List.of(new NfWorkflow(null, List.of("PROC"),
                                                List.of("samplesheet.csv", "*.fastq.gz"))),
                                Collections.emptyList(),
                                Collections.emptyList());
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
         * or OOM in renderDag. The renderer should complete and produce output that
         * still contains the process commit.
         */
        @Test
        void testSelfLoopConnectionDoesNotHang() {
                ParsedPipeline p = pipeline(
                                List.of(new NfProcess("A")),
                                List.<String[]>of(new String[] { "A", "A" }));
                // Must complete without hanging or throwing
                String result = assertTimeoutPreemptively(
                                java.time.Duration.ofSeconds(5),
                                () -> RENDERER.render(p),
                                "render() hung on a self-loop connection");
                assertTrue(result.contains("commit id: \"A\""),
                                "Process A should still appear in output:\n" + result);
        }

        /**
         * A two-node cycle (A → B, B → A) must not cause an infinite loop in tracePath.
         */
        @Test
        void testTwoNodeCycleDoesNotHang() {
                ParsedPipeline p = pipeline(
                                List.of(new NfProcess("A"), new NfProcess("B")),
                                List.<String[]>of(new String[] { "A", "B" }, new String[] { "B", "A" }));
                String result = assertTimeoutPreemptively(
                                java.time.Duration.ofSeconds(5),
                                () -> RENDERER.render(p),
                                "render() hung on a two-node cycle");
                assertTrue(result.contains("commit id: \"A\"") || result.contains("commit id: \"B\""),
                                "At least one process should appear in output:\n" + result);
        }

        /**
         * An off-chain cycle (A → B → A, both not on the main path) must not cause an
         * infinite loop in emitOffChainWithChannels.
         *
         * <p>
         * Graph: X → Z (main chain), X → A, A → B, B → A (off-chain cycle).
         */
        @Test
        void testOffChainCycleDoesNotHang() {
                ParsedPipeline p = pipeline(
                                List.of(new NfProcess("X"), new NfProcess("Z"),
                                                new NfProcess("A"), new NfProcess("B")),
                                List.<String[]>of(
                                                new String[] { "X", "Z" }, new String[] { "X", "A" },
                                                new String[] { "A", "B" }, new String[] { "B", "A" }));
                String result = assertTimeoutPreemptively(
                                java.time.Duration.ofSeconds(5),
                                () -> RENDERER.render(p),
                                "render() hung on an off-chain cycle");
                assertTrue(result.contains("commit id: \"X\""),
                                "Process X should appear in output:\n" + result);
        }

        /**
         * Off-chain nodes that are shared by multiple branches must not be emitted more
         * than once.
         *
         * <p>
         * Graph: START → M1 → M2 → M3 → M4 (main path, dist=4), plus two off-chains
         * that
         * converge: START → BRANCH_A → SHARED → TAIL and START → BRANCH_B → SHARED →
         * TAIL.
         * SHARED and TAIL are reachable from both BRANCH_A and BRANCH_B but must each
         * appear
         * in the diagram only once.
         */
        @Test
        void testSharedOffChainNodesAreDeduplicatedAcrossBranches() {
                NfProcess start = new NfProcess("START");
                NfProcess m1 = new NfProcess("M1");
                NfProcess m2 = new NfProcess("M2");
                NfProcess m3 = new NfProcess("M3");
                NfProcess m4 = new NfProcess("M4");
                NfProcess branchA = new NfProcess("BRANCH_A");
                NfProcess branchB = new NfProcess("BRANCH_B");
                NfProcess shared = new NfProcess("SHARED");
                NfProcess tail = new NfProcess("TAIL");
                ParsedPipeline p = pipeline(
                                List.of(start, m1, m2, m3, m4, branchA, branchB, shared, tail),
                                List.<String[]>of(
                                                new String[] { "START", "M1" },
                                                new String[] { "M1", "M2" },
                                                new String[] { "M2", "M3" },
                                                new String[] { "M3", "M4" },
                                                new String[] { "START", "BRANCH_A" },
                                                new String[] { "START", "BRANCH_B" },
                                                new String[] { "BRANCH_A", "SHARED" },
                                                new String[] { "BRANCH_B", "SHARED" },
                                                new String[] { "SHARED", "TAIL" }));
                String result = RENDERER.render(p);
                // SHARED and TAIL must each appear exactly once in the output
                long sharedCount = Arrays.stream(result.split("\n"))
                                .filter(l -> l.contains("commit id: \"SHARED\"")).count();
                long tailCount = Arrays.stream(result.split("\n"))
                                .filter(l -> l.contains("commit id: \"TAIL\"")).count();
                assertEquals(1, sharedCount,
                                "SHARED process should be committed exactly once:\n" + result);
                assertEquals(1, tailCount,
                                "TAIL process should be committed exactly once:\n" + result);
        }

        // -------------------------------------------------------------------------
        // Input HIGHLIGHT tests
        // -------------------------------------------------------------------------

        /**
         * A process with a single string-literal input path pattern should emit an
         * input
         * HIGHLIGHT commit before the process commit.
         */
        @Test
        void testSingleInputPatternEmittedAsHighlight() {
                NfProcess proc = new NfProcess("ALIGN",
                                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                List.of("*.fastq.gz"), // inputs
                                List.of("*.bam")); // outputs
                String result = RENDERER.render(pipeline(proc));
                // Input HIGHLIGHT commit should appear before the process commit
                assertTrue(result.contains(
                                "commit id: \"ALIGN: input: *.fastq.gz\" type: HIGHLIGHT tag: \"*.fastq.gz\""),
                                "Expected single-input HIGHLIGHT:\n" + result);
                int inputIdx = result.indexOf("ALIGN: input: *.fastq.gz");
                int procIdx = result.indexOf("commit id: \"ALIGN\"");
                assertTrue(inputIdx < procIdx,
                                "Input HIGHLIGHT should appear before the process commit:\n" + result);
        }

        /**
         * A process with multiple input patterns should emit a single aggregated
         * HIGHLIGHT
         * commit with all patterns shown (up to 2 explicit tags, overflow as +N more).
         */
        @Test
        void testMultipleInputPatternsAreAggregated() {
                NfProcess proc = new NfProcess("PROC",
                                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                List.of("*.fastq.gz", "reference.fa", "annotation.gtf"), // 3 inputs
                                Collections.emptyList());
                String result = RENDERER.render(pipeline(proc));
                // Commit ID uses wildcard when there are multiple inputs
                assertTrue(result.contains("commit id: \"PROC: input: *\" type: HIGHLIGHT"),
                                "Expected wildcard commit ID for multiple inputs:\n" + result);
                // First two patterns shown as explicit tags
                assertTrue(result.contains("tag: \"*.fastq.gz\""),
                                "Expected first input pattern as tag:\n" + result);
                assertTrue(result.contains("tag: \"reference.fa\""),
                                "Expected second input pattern as tag:\n" + result);
                // Third pattern summarised as +1 more
                assertTrue(result.contains("tag: \"+1 more\""),
                                "Expected overflow tag for third input:\n" + result);
        }

        /**
         * A process with no input patterns (only variable-name inputs, which are not
         * collected by the parser) should NOT emit an input HIGHLIGHT commit.
         */
        @Test
        void testProcessWithNoInputsEmitsNoInputHighlight() {
                NfProcess proc = new NfProcess("FASTQC",
                                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(), // no string-literal input patterns
                                List.of("*.html", "*.zip"));
                String result = RENDERER.render(pipeline(proc));
                assertFalse(result.contains("input:"),
                                "Should not emit input HIGHLIGHT when process has no string-literal input patterns:\n"
                                                + result);
        }

        /**
         * In DAG rendering, the input HIGHLIGHT for a process should be suppressed when
         * all of
         * its input patterns are already produced by a direct predecessor (issue 1).
         * The cherry-pick still appears (ALIGN is on a different branch) and references
         * the
         * predecessor process name directly (issue 3).
         */
        @Test
        void testInputHighlightBeforeCherryPickInDagRendering() {
                NfProcess align = new NfProcess("ALIGN",
                                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(),
                                List.of("*.bam"));
                // SORT has *.bam as both its input and ALIGN's output → input HIGHLIGHT
                // suppressed.
                NfProcess sort = new NfProcess("SORT",
                                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                List.of("*.bam"), // inputs covered by ALIGN's outputs
                                List.of("*.sorted.bam"));
                NfProcess qc = new NfProcess("QC");
                // ALIGN → QC (main path); ALIGN → SORT (branch)
                ParsedPipeline p = pipeline(
                                List.of(align, sort, qc),
                                List.of(new String[] { "ALIGN", "QC" }, new String[] { "ALIGN", "SORT" }));
                String result = RENDERER.render(p);
                // Input HIGHLIGHT for SORT suppressed (*.bam covered by ALIGN's outputs – issue
                // 1)
                assertFalse(result.contains("SORT: input:"),
                                "Input HIGHLIGHT for SORT should be suppressed when covered by ALIGN's outputs:\n"
                                                + result);
                // Cherry-pick for ALIGN still present (on different branch) – references
                // process name (issue 3)
                assertTrue(result.contains("cherry-pick id: \"ALIGN\""),
                                "Expected cherry-pick referencing ALIGN process name:\n" + result);
                int cherryPickIdx = result.indexOf("cherry-pick");
                int sortCommitIdx = result.indexOf("commit id: \"SORT\"");
                assertTrue(cherryPickIdx < sortCommitIdx,
                                "Cherry-pick should appear before process commit:\n" + result);
        }

        /**
         * Issue 1: In a simple linear chain (A → B) where B's inputs exactly match A's
         * outputs,
         * the input HIGHLIGHT commit for B is suppressed (no redundant intermediate
         * file node).
         */
        @Test
        void testInputHighlightSuppressedInLinearChainWithSameFiles() {
                NfProcess a = new NfProcess("TRIM",
                                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(),
                                List.of("*.trimmed.fastq.gz")); // outputs *.trimmed.fastq.gz
                NfProcess b = new NfProcess("ALIGN",
                                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                List.of("*.trimmed.fastq.gz"), // inputs match TRIM's outputs
                                List.of("*.bam"));
                ParsedPipeline p = pipeline(
                                List.of(a, b),
                                List.<String[]>of(new String[] { "TRIM", "ALIGN" }));
                String result = RENDERER.render(p);
                // Input HIGHLIGHT for ALIGN suppressed (*.trimmed.fastq.gz covered by TRIM's
                // outputs)
                assertFalse(result.contains("ALIGN: input:"),
                                "Input HIGHLIGHT for ALIGN should be suppressed in linear chain with same files:\n"
                                                + result);
                // Both process commits are still present
                assertTrue(result.contains("commit id: \"TRIM\" tag: \"*.trimmed.fastq.gz\""),
                                "TRIM should still appear with its output tag:\n" + result);
                assertTrue(result.contains("commit id: \"ALIGN\" tag: \"*.bam\""),
                                "ALIGN should still appear with its output tag:\n" + result);
        }

        /**
         * In DAG rendering, the input HIGHLIGHT is NOT suppressed when the process's
         * input
         * patterns are different from its predecessor's output patterns (different
         * files).
         * Issue 1 only suppresses when all inputs are covered by predecessor outputs.
         */
        @Test
        void testInputHighlightKeptWhenNotCoveredByPredecessor() {
                NfProcess align = new NfProcess("ALIGN",
                                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(),
                                List.of("*.bam")); // outputs *.bam
                // ANNOTATE reads reference.fa which ALIGN does NOT produce → input HIGHLIGHT
                // kept
                NfProcess annotate = new NfProcess("ANNOTATE",
                                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                List.of("reference.fa"), // not covered by ALIGN's outputs
                                List.of("*.annotated.bam"));
                ParsedPipeline p = pipeline(
                                List.of(align, annotate),
                                List.<String[]>of(new String[] { "ALIGN", "ANNOTATE" }));
                String result = RENDERER.render(p);
                assertTrue(result.contains("ANNOTATE: input: reference.fa"),
                                "Input HIGHLIGHT should be kept when inputs differ from predecessor outputs:\n"
                                                + result);
        }

        /**
         * Output patterns with three or more outputs should show 2 explicit tags plus
         * "+N more".
         */
        @Test
        void testMoreThanTwoOutputsShowsOverflowTag() {
                NfProcess proc = new NfProcess("MULTIQC",
                                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                Collections.emptyList(),
                                List.of("report.html", "report.zip", "versions.yml"));
                String result = RENDERER.render(pipeline(proc));
                // Issue 4: inline tags on process commit
                assertTrue(result.contains(
                                "commit id: \"MULTIQC\" tag: \"report.html\" tag: \"report.zip\" tag: \"+1 more\""),
                                "Expected 2 explicit tags + overflow:\n" + result);
        }
}
