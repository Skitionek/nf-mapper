#!/usr/bin/env nextflow

// Workflow that uses parenthesised multi-argument and zero-argument call
// syntax throughout (DSL2 / nf-core style).
//
// This fixture was introduced to test that the parser correctly handles:
//   - PROCESS(arg1, arg2)  multi-arg  → Groovy path_expression in the AST
//   - PROCESS()            no-arg     → Groovy path_expression in the AST
//   - SUBWORKFLOW()        empty call → from the entry workflow
//
// Compare with complex_workflow.nf which uses single-argument
// command-expression syntax that the parser already handled.

include { FASTQC      } from './modules/fastqc'
include { TRIM_GALORE } from './modules/trim_galore'

process ALIGN {
    input:
        tuple val(meta), path(reads)
        path genome

    output:
        tuple val(meta), path("*.bam"), emit: bam

    script:
    """
    bwa mem ${genome} ${reads} > aligned.bam
    """
}

process MULTIQC {
    input:
        path reports
        path config

    output:
        path "multiqc_report.html", emit: report

    script:
    """
    multiqc --config ${config} .
    """
}

workflow PIPELINE {
    take:
        reads
        genome

    main:
    FASTQC(reads, params.outdir)
    TRIM_GALORE(reads, params.adapter)
    ALIGN(TRIM_GALORE.out.trimmed, genome)
    MULTIQC(
        ALIGN.out.bam,
        params.multiqc_config,
    )

    emit:
    report = MULTIQC.out.report
}

workflow {
    main:
    PIPELINE(
        params.reads,
        params.genome,
    )
}
