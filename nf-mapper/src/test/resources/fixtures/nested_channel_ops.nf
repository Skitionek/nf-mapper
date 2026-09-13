process ALIGN {
    input:
      path reads
    output:
      path "*.bam", emit: bam
    script: 'echo align'
}
process CALL_VARIANTS {
    input:
      path bam
    output:
      path "*.vcf", emit: vcf
    script: 'echo call_variants'
}
process ANNOTATE {
    input:
      path vcf
    output:
      path "*.annotated.vcf", emit: vcf
    script: 'echo annotate'
}
process SUMMARY {
    input:
      path summary_input
    output:
      path "*.summary.txt", emit: summary
    script: 'echo summary'
}
workflow {
    main:
      ALIGN(params.reads)
      CALL_VARIANTS(params.reads)
      // nested channel-op call arg: mix two upstream outputs into one downstream call
      ANNOTATE(ALIGN.out.bam.mix(CALL_VARIANTS.out.vcf))
      // .collect() as a call arg
      SUMMARY(ANNOTATE.out.vcf.collect())
}
