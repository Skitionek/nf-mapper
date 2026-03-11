#!/usr/bin/env nextflow

// Simple workflow with two processes and one connection

process FASTQC {
    container 'docker://biocontainers/fastqc:v0.11.9_cv8'

    input:
        tuple val(meta), path(reads)

    output:
        tuple val(meta), path("*.html"), emit: html
        tuple val(meta), path("*.zip"),  emit: zip

    script:
    """
    fastqc $reads
    """
}

process MULTIQC {
    conda 'bioconda::multiqc=1.14'

    input:
        path reports

    output:
        path "multiqc_report.html", emit: report

    script:
    """
    multiqc .
    """
}

workflow {
    reads_ch = Channel.fromFilePairs(params.reads)
    FASTQC(reads_ch)
    MULTIQC(FASTQC.out.zip.collect())
}
