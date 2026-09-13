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
      bam_ch = ALIGN(params.reads)
      sorted_ch = SORT(bam_ch)
}
