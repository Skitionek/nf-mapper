package com.nfmapper.examples;

import java.util.Collections;
import java.util.List;

import com.nfmapper.mermaid.MermaidRenderer;
import com.nfmapper.model.NfProcess;
import com.nfmapper.model.ParsedPipeline;

/**
 * Example demonstrating the MetroMapMermaidRenderer output.
 */
public class MetroMapExample {

        public static void main(String[] args) {
                // Create a sample pipeline similar to a bioinformatics workflow
                NfProcess fastqc = new NfProcess("FASTQC",
                                Collections.singletonList("docker://biocontainers/fastqc:v0.11.9_cv8"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.singletonList("*.fastq.gz"),
                                List.of("*.html", "*.zip"));

                NfProcess trim = new NfProcess("TRIMMOMATIC",
                                Collections.singletonList("docker://biocontainers/trimmomatic:0.39"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.singletonList("*.fastq.gz"),
                                Collections.singletonList("*_trimmed.fastq.gz"));

                NfProcess align = new NfProcess("STAR_ALIGN",
                                Collections.emptyList(),
                                Collections.singletonList("bioconda::star=2.7.10a"),
                                Collections.emptyList(),
                                Collections.singletonList("*_trimmed.fastq.gz"),
                                Collections.singletonList("*.bam"));

                NfProcess sort = new NfProcess("SAMTOOLS_SORT",
                                Collections.singletonList("docker://biocontainers/samtools:1.15"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.singletonList("*.bam"),
                                Collections.singletonList("*.sorted.bam"));

                NfProcess multiqc = new NfProcess("MULTIQC",
                                Collections.emptyList(),
                                Collections.singletonList("bioconda::multiqc=1.14"),
                                Collections.emptyList(),
                                List.of("*.html", "*.zip"),
                                Collections.singletonList("multiqc_report.html"));

                // Create connections: FASTQC and TRIM run in parallel,
                // then ALIGN -> SORT, and MULTIQC collects QC reports
                List<String[]> connections = List.of(
                                new String[] { "TRIMMOMATIC", "STAR_ALIGN" },
                                new String[] { "STAR_ALIGN", "SAMTOOLS_SORT" },
                                new String[] { "FASTQC", "MULTIQC" });

                ParsedPipeline pipeline = new ParsedPipeline(
                                List.of(fastqc, trim, align, sort, multiqc),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                connections);

                // Render as gitGraph
                MermaidRenderer renderer = new MermaidRenderer();

                System.out.println("# Metro Map Style (Left-to-Right)");
                System.out.println();
                System.out.println("```mermaid");
                System.out.println(renderer.render(pipeline, "RNA-seq Pipeline", null));
                System.out.println("```");
                System.out.println();

                System.out.println("# Metro Map Style (Top-to-Down)");
                System.out.println();
                System.out.println("```mermaid");
                System.out.println(renderer.render(pipeline, "RNA-seq Pipeline", null));
                System.out.println("```");
        }
}
