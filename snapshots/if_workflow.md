# if_workflow

> Generated from `nf-mapper/src/test/resources/fixtures/if_workflow.nf`

```mermaid
---
title: If-Statement Workflow
---
%%{init: {'theme': 'base', 'themeVariables': {'git0': '#24B064', 'gitInv0': '#ffffff', 'git1': '#FA7F19', 'gitInv1': '#ffffff', 'git2': '#0570b0', 'gitInv2': '#ffffff', 'git3': '#e63946', 'gitInv3': '#ffffff', 'git4': '#9b59b6', 'gitInv4': '#ffffff', 'git5': '#f5c542', 'gitInv5': '#000000', 'git6': '#1abc9c', 'gitInv6': '#ffffff', 'git7': '#7b2d3b', 'gitInv7': '#ffffff'}, 'gitGraph': {'showBranches': true, 'parallelCommits': false}} }%%
gitGraph LR:
   checkout main
   commit id: "TRIM"
   commit id: "TRIM: *.trimmed.fastq.gz" type: HIGHLIGHT tag: "*.trimmed.fastq.gz"
   commit id: "ALIGN"
   commit id: "ALIGN: *.bam" type: HIGHLIGHT tag: "*.bam"
   branch QC
   checkout QC
   cherry-pick id: "ALIGN: *.bam"
   commit id: "if: QC" type: REVERSE
   commit id: "QC"
   commit id: "QC: *.qc.txt" type: HIGHLIGHT tag: "*.qc.txt"
   checkout main
   commit id: "COUNT"
   commit id: "COUNT: *.counts.txt" type: HIGHLIGHT tag: "*.counts.txt"
```
