#!/usr/bin/env nextflow

// Multi-step RNA-seq pipeline demonstrating includes and a named workflow

include { FASTQC     } from './modules/fastqc'
include { TRIMGALORE } from './modules/trimgalore'

process STAR_ALIGN {
    container 'docker://nfcore/star:2.7.10a'

    input:
        tuple val(meta), path(reads)

    output:
        tuple val(meta), path("*.bam"), emit: bam

    script:
    """
    STAR --runThreadN ${task.cpus} --readFilesIn ${reads}
    """
}

process SAMTOOLS_SORT {
    conda 'bioconda::samtools=1.15'

    input:
        tuple val(meta), path(bam)

    output:
        tuple val(meta), path("*.sorted.bam"), emit: sorted_bam

    script:
    """
    samtools sort -o sorted.bam ${bam}
    """
}

process FEATURECOUNTS {
    input:
        tuple val(meta), path(bam)

    output:
        tuple val(meta), path("*.counts.txt"), emit: counts

    script:
    """
    featureCounts -a annotation.gtf -o counts.txt ${bam}
    """
}

workflow RNASEQ {
    take:
        reads

    main:
        FASTQC(reads)
        TRIMGALORE(reads)
        STAR_ALIGN(TRIMGALORE.out.trimmed)
        SAMTOOLS_SORT(STAR_ALIGN.out.bam)
        FEATURECOUNTS(SAMTOOLS_SORT.out.sorted_bam)

    emit:
        counts = FEATURECOUNTS.out.counts
}
