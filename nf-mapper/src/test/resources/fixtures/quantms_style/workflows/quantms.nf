#!/usr/bin/env nextflow
// Sub-workflow included by quantms_style/main.nf.
// Mirrors the structure of bigbio/quantms workflows/quantms.nf.

include { INPUT_CHECK        } from '../subworkflows/local/input_check/main'
include { CREATE_INPUT_CHANNEL } from '../subworkflows/local/create_input/main'
include { FILE_PREPARATION   } from '../subworkflows/local/file_prep/main'
include { PMULTIQC as SUMMARY_PIPELINE } from '../modules/local/pmultiqc/main'

workflow QUANTMS {

    main:
    INPUT_CHECK (
        file(params.input)
    )
    CREATE_INPUT_CHANNEL (
        INPUT_CHECK.out.ch_input_file,
        INPUT_CHECK.out.is_sdrf,
    )
    FILE_PREPARATION (
        CREATE_INPUT_CHANNEL.out.ch_meta_config
    )
    SUMMARY_PIPELINE (
        FILE_PREPARATION.out.results
    )

    emit:
    multiqc_report = SUMMARY_PIPELINE.out.report
}
