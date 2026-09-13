// Regression fixture for a known nf-lang 25.04.4 grammar limitation:
// a process section label (e.g. `output:`) placed on the SAME physical
// line as the opening brace `process A {` fails to parse, even though
// the semantically identical process with the label on its own line
// parses fine. See ParserTest#testInlineSectionLabelIsKnownNfLangLimitation.
process A { output: path "*.bam", emit: bam
    script:
    'echo a'
}
workflow {
    A()
}
