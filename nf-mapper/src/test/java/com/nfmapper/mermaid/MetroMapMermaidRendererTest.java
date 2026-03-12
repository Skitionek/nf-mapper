package com.nfmapper.mermaid;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nfmapper.model.NfProcess;
import com.nfmapper.model.NfWorkflow;
import com.nfmapper.model.ParsedPipeline;

class MetroMapMermaidRendererTest {

    private static final MetroMapMermaidRenderer METRO = new MetroMapMermaidRenderer();
    private static final MermaidRenderer DEFAULT = new MermaidRenderer();

    private ParsedPipeline simplePipeline() {
        NfProcess fastqc = new NfProcess("FASTQC",
                List.of("biocontainers/fastqc:v0.11.9"),
                Collections.emptyList(),
                Collections.emptyList(),
                List.of("*.fastq.gz"),
                List.of("*.html", "*.zip"));

        NfProcess multiqc = new NfProcess("MULTIQC",
                Collections.emptyList(),
                List.of("bioconda::multiqc=1.14"),
                Collections.emptyList(),
                List.of("*.zip"),
                List.of("multiqc_report.html"));

        List<String[]> connections = Collections.singletonList(new String[] { "FASTQC", "MULTIQC" });

        return new ParsedPipeline(
                List.of(fastqc, multiqc),
                Collections.emptyList(),
                Collections.emptyList(),
                connections);
    }

    private ParsedPipeline flatPipeline() {
        NfProcess proc1 = new NfProcess("PROCESS_A",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                List.of("input.txt"),
                List.of("output.txt"));

        NfProcess proc2 = new NfProcess("PROCESS_B",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                List.of("data.csv"),
                List.of("result.csv"));

        NfWorkflow entryWf = new NfWorkflow(
                null,
                List.of("PROCESS_A", "PROCESS_B"),
                List.of("samplesheet.csv", "data/*_{1,2}.fastq.gz"));

        return new ParsedPipeline(
                List.of(proc1, proc2),
                List.of(entryWf),
                Collections.emptyList(),
                Collections.emptyList());
    }

    @Test
    void testBasicGitGraphStructure() {
        String result = METRO.render(simplePipeline(), null, null);

        assertTrue(result.contains("gitGraph LR:"), "Should render gitGraph syntax");
        assertTrue(result.contains("checkout main"), "Should checkout main branch");
        assertTrue(result.contains("commit id: \"FASTQC\""), "Should contain FASTQC commit");
        assertTrue(result.contains("commit id: \"MULTIQC\""), "Should contain MULTIQC commit");
    }

    @Test
    void testWithTitle() {
        String result = METRO.render(simplePipeline(), "My Pipeline", null);

        assertTrue(result.contains("---"), "Should have title block markers");
        assertTrue(result.contains("title: My Pipeline"), "Should contain title");
    }

    @Test
    void testTransformIsDeterministic() {
        String resultFirst = METRO.render(simplePipeline(), null, null);
        String resultSecond = METRO.render(simplePipeline(), null, null);

        assertTrue(resultFirst.contains("gitGraph LR:"), "Should render gitGraph LR");
        assertEquals(resultFirst, resultSecond, "Transformer output should be deterministic");
    }

    @Test
    void testFlatPipelineStillRendersGitGraph() {
        String result = METRO.render(flatPipeline(), null, null);

        assertTrue(result.contains("gitGraph LR:"), "Should render gitGraph syntax");
        assertTrue(result.contains("commit id: \"PROCESS_A\""), "Should contain PROCESS_A commit");
        assertTrue(result.contains("commit id: \"PROCESS_B\""), "Should contain PROCESS_B commit");
    }

    @Test
    void testUsesGitGraphNotFlowchart() {
        String result = METRO.render(simplePipeline(), null, null);

        assertTrue(result.contains("gitGraph"), "Should contain gitGraph");
        assertFalse(result.contains("flowchart"), "Should not contain flowchart syntax");
        assertFalse(result.contains("%% Metro map styling"), "Should not emit flowchart styling block");
    }

    @Test
    void testMatchesDefaultRendererOutput() {
        ParsedPipeline pipeline = simplePipeline();

        String metro = METRO.render(pipeline, "Same Title", null);
        String standard = DEFAULT.render(pipeline, "Same Title", null);

        assertEquals(standard, metro, "Metro renderer should emit the same gitGraph output as default renderer");
    }

    @Test
    void testEmptyPipeline() {
        ParsedPipeline empty = new ParsedPipeline(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());

        String result = METRO.render(empty, null, null);
        assertNotNull(result, "Should handle empty pipeline");
        assertTrue(result.contains("gitGraph"), "Should still render gitGraph declaration");
    }
}
