package com.nfmapper.mermaid;

class MermaidRendererConditionalSuiteTest extends MermaidRendererTest {

    @Override
    protected MermaidRenderer renderer() {
        return new ConditionalBranchMermaidRenderer();
    }

    @Override
    protected boolean expectConditionalBranchNameInDagConditionalTest() {
        return true;
    }

    @Override
    protected boolean expectFlatWorkflowBranches() {
        return false;
    }
}
