#!/usr/bin/env nextflow
// Minimal reproduction of the bigbio/quantms main.nf pattern:
// an entry workflow that delegates to a named local wrapper workflow which
// in turn calls an imported subworkflow.  This fixture is used to verify
// that nf-mapper correctly expands the included subworkflow's processes
// when the referenced file exists on disk.

include { QUANTMS           } from './workflows/quantms'
include { PIPELINE_COMPLETION } from './subworkflows/local/utils'

workflow BIGBIO_QUANTMS {

    main:
    QUANTMS ()

    emit:
    multiqc_report = QUANTMS.out.multiqc_report
}

workflow {

    main:
    BIGBIO_QUANTMS ()

    PIPELINE_COMPLETION (
        params.email,
        params.outdir,
        BIGBIO_QUANTMS.out.multiqc_report
    )
}
