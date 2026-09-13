process TRIM {
    input:
      path reads
    output:
      path "*.trimmed.fastq.gz", emit: trimmed
    script: 'echo trim'
}
process ALIGN {
    input:
      path reads
    output:
      path "*.bam", emit: bam
    script: 'echo align'
}
process SORT {
    input:
      path bam
    output:
      path "*.sorted.bam", emit: sorted
    script: 'echo sort'
}
workflow {
    main:
      // Channel source piped through a chain of processes: A | B | C
      Channel.fromPath(params.reads) | TRIM | ALIGN | SORT
}
