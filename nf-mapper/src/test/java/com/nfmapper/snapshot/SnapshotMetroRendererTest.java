package com.nfmapper.snapshot;

import com.nfmapper.mermaid.MermaidRenderer;
import com.nfmapper.mermaid.MetroMapMermaidRenderer;

class SnapshotMetroRendererTest extends SnapshotTest {

    @Override
    protected MermaidRenderer renderer() {
        return new MetroMapMermaidRenderer();
    }

    @Override
    protected String snapshotRendererSuffix() {
        return "metro";
    }
}
