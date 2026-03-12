package com.nfmapper.mermaid;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.nfmapper.model.NfProcess;
import com.nfmapper.model.ParsedPipeline;

class RendererThemeMatrixTest {

    private ParsedPipeline singleProcessPipeline() {
        return new ParsedPipeline(
                List.of(new NfProcess("HELLO")),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
    }

    private ParsedPipeline conditionalDagPipeline() {
        Map<String, String[]> conditionalInfo = new LinkedHashMap<>();
        conditionalInfo.put("QC", new String[] { "0", "params.run_qc" });
        return new ParsedPipeline(
                List.of(new NfProcess("ALIGN"), new NfProcess("QC")),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonList(new String[] { "ALIGN", "QC" }),
                conditionalInfo);
    }

    @Test
    void testTransformerRendererThemeMatrix() {
        record Case(String name, MermaidRenderer renderer, ParsedPipeline pipeline, String expectedTheme,
                String expectedToken) {
        }

        List<Case> cases = List.of(
                new Case(
                        "default + gitgraph + nf-core",
                        new GitGraphMermaidRenderer(new NfCoreMermaidTheme()),
                        singleProcessPipeline(),
                        "'theme': 'base'",
                        "commit id: \"HELLO\""),
                new Case(
                        "default + gitgraph + plain",
                        new GitGraphMermaidRenderer(new PlainMermaidTheme()),
                        singleProcessPipeline(),
                        "'theme': 'default'",
                        "commit id: \"HELLO\""),
                new Case(
                        "metro + metro-map + nf-core",
                        new MetroMapMermaidRenderer(new NfCoreMermaidTheme()),
                        singleProcessPipeline(),
                        "'theme': 'base'",
                        "commit id: \"HELLO\""),
                new Case(
                        "metro + metro-map + plain",
                        new MetroMapMermaidRenderer(new PlainMermaidTheme()),
                        singleProcessPipeline(),
                        "'theme': 'default'",
                        "commit id: \"HELLO\""),
                new Case(
                        "conditional + gitgraph + plain",
                        new ConditionalBranchMermaidRenderer(
                                new GitGraphMermaidRenderer(new PlainMermaidTheme()),
                                new PlainMermaidTheme()),
                        conditionalDagPipeline(),
                        "'theme': 'default'",
                        "type: REVERSE"),
                new Case(
                        "conditional + metro-map + nf-core",
                        new ConditionalBranchMermaidRenderer(
                                new MetroMapMermaidRenderer(new NfCoreMermaidTheme()),
                                new NfCoreMermaidTheme()),
                        conditionalDagPipeline(),
                        "'theme': 'base'",
                        "type: REVERSE"));

        for (Case testCase : cases) {
            String diagram = testCase.renderer().render(testCase.pipeline(), "Matrix", null);
            assertTrue(diagram.contains("gitGraph LR:"), "Missing gitGraph for case: " + testCase.name());
            assertTrue(diagram.contains(testCase.expectedTheme()),
                    "Unexpected theme for case: " + testCase.name() + "\n" + diagram);
            assertTrue(diagram.contains(testCase.expectedToken()),
                    "Missing expected token for case: " + testCase.name() + "\n" + diagram);
        }
    }
}
