package com.nfmapper.mermaid;

class MermaidRendererMetroSuiteTest extends MermaidRendererTest {

    @Override
    protected MermaidRenderer renderer() {
        return new MetroMapMermaidRenderer();
    }
}
