#!/usr/bin/env nextflow

// Test fixture: workflow main block contains file-reference channel calls
// whose string-literal patterns should appear in the generated graph.

process VALIDATE_INPUT {
    input:
        tuple val(meta), path(samplesheet)

    output:
        tuple val(meta), path("*.validated.csv"), emit: csv

    script:
    """
    validate_input.py $samplesheet
    """
}

process FASTQC {
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

workflow {

    main:
    samplesheet_ch = Channel.fromPath("samplesheet.csv")
    reads_ch       = Channel.fromFilePairs("data/*_{1,2}.fastq.gz")

    VALIDATE_INPUT(samplesheet_ch)
    FASTQC(reads_ch)
}
