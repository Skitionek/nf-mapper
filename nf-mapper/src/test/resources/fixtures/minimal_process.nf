#!/usr/bin/env nextflow

// Minimal pipeline with a single process and no workflow block

process HELLO {
    script:
    """
    echo "Hello, Nextflow!"
    """
}
