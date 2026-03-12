package com.nfmapper.snapshot;

import com.nfmapper.mermaid.ConditionalBranchMermaidRenderer;
import com.nfmapper.mermaid.MermaidRenderer;

class SnapshotConditionalRendererTest extends SnapshotTest {

    @Override
    protected MermaidRenderer renderer() {
        return new ConditionalBranchMermaidRenderer();
    }

    @Override
    protected String snapshotRendererSuffix() {
        return "conditional";
    }

    @Override
    protected boolean expectWorkflowCallBranchesInSnapshot() {
        return false;
    }
}
