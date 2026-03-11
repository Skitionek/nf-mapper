package com.nfmapper.mermaid;

import com.nfmapper.model.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MermaidRendererTest {

    private static final MermaidRenderer RENDERER = new MermaidRenderer();
    private static final String DEFAULT_INIT =
        "%%{init: {'gitGraph': {'showBranches': false, 'parallelCommits': true}} }%%";

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
        assertEquals(DEFAULT_INIT, lines[0]);
        assertEquals("gitGraph LR:", lines[1]);
        assertEquals("   checkout main", lines[2]);
    }

    @Test void testTitleAddsFrontMatter() {
        String result = RENDERER.render(pipeline(), "My Pipeline", null);
        String[] lines = result.split("\n");
        assertEquals("---", lines[0]);
        assertEquals("title: My Pipeline", lines[1]);
        assertEquals("---", lines[2]);
        assertEquals(DEFAULT_INIT, lines[3]);
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
        // Two processes with no direct connection → flat rendering → branch
        ParsedPipeline p = new ParsedPipeline(
            List.of(new NfProcess("A"), new NfProcess("B")),
            List.of(new NfWorkflow(null, List.of("A", "B"))),
            Collections.emptyList(),
            Collections.emptyList()
        );
        String result = RENDERER.render(p);
        assertTrue(result.contains("branch branch_1"));
    }

    @Test void testChannelHighlightCommits() {
        NfProcess proc = new NfProcess("ALIGN", Collections.emptyList(), Collections.emptyList(),
                                        Collections.emptyList(), Collections.emptyList(),
                                        List.of("*.bam"));
        String result = RENDERER.render(pipeline(proc));
        assertTrue(result.contains("commit id: \"ALIGN: *.bam\" type: HIGHLIGHT tag: \"bam\""),
            "Result was:\n" + result);
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
        config.put("showBranches", true);
        String result = RENDERER.render(pipeline(), null, config);
        assertTrue(result.contains("'showBranches': true"));
        assertTrue(result.contains("'parallelCommits': true")); // default preserved
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
        assertTrue(result.contains("\"FASTQC: *.html\" type: HIGHLIGHT tag: \"html\""));
        assertTrue(result.contains("\"FASTQC: *.zip\" type: HIGHLIGHT tag: \"zip\""));
    }
}
