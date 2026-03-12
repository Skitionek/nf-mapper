# scenario_workflow_call_branches

```mermaid
---
title: Workflow Call Branches
---
%%{init: {'theme': 'base', 'themeVariables': {'git0': '#24B064', 'gitInv0': '#ffffff', 'git1': '#FA7F19', 'gitInv1': '#ffffff', 'git2': '#0570b0', 'gitInv2': '#ffffff', 'git3': '#e63946', 'gitInv3': '#ffffff', 'git4': '#9b59b6', 'gitInv4': '#ffffff', 'git5': '#f5c542', 'gitInv5': '#000000', 'git6': '#1abc9c', 'gitInv6': '#ffffff', 'git7': '#7b2d3b', 'gitInv7': '#ffffff'}, 'gitGraph': {'showBranches': true, 'parallelCommits': false}} }%%
gitGraph LR:
   checkout main
   commit id: "FASTQC"
   commit id: "FASTQC: *.html" type: HIGHLIGHT tag: "*.html" tag: "*.zip"
   branch TRIMGALORE
   checkout TRIMGALORE
   commit id: "TRIMGALORE"
   commit id: "TRIMGALORE: *.trimmed.fastq.gz" type: HIGHLIGHT tag: "*.trimmed.fastq.gz"
   checkout main
   branch MULTIQC
   checkout MULTIQC
   commit id: "MULTIQC"
   commit id: "MULTIQC: multiqc_report.html" type: HIGHLIGHT tag: "multiqc_report.html"
   checkout main
```
