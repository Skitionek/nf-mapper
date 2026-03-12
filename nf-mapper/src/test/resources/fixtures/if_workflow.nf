#!/usr/bin/env nextflow

// Test fixture: named sub-workflow called from the entry workflow, plus an
// if-statement block that contains a conditional process.

process TRIM {
    output:
        path "*.trimmed.fastq.gz", emit: trimmed

    script:
    """
    echo trim
    """
}

process ALIGN {
    input:
        path reads

    output:
        path "*.bam", emit: bam

    script:
    """
    echo align
    """
}

process QC {
    input:
        path bam

    output:
        path "*.qc.txt", emit: report

    script:
    """
    echo qc
    """
}

process COUNT {
    input:
        path bam

    output:
        path "*.counts.txt", emit: counts

    script:
    """
    echo count
    """
}

// Named sub-workflow – called from the entry workflow below (forward reference
// scenario: COUNT_WF is registered via pre-pass before the entry workflow is
// parsed, so its call is correctly detected even when defined after the caller).
workflow COUNT_WF {
    take:
        bam

    main:
        COUNT(bam)

    emit:
        counts = COUNT.out.counts
}

workflow {
    TRIM()
    ALIGN(TRIM.out.trimmed)

    if (params.run_qc) {
        QC(ALIGN.out.bam)
    }

    COUNT_WF(ALIGN.out.bam)
}
