package com.nfmapper.cli;

import java.util.Locale;

import com.nfmapper.mermaid.*;

final class MermaidRendererFactory {
    MermaidRenderer create(String name, MermaidTheme theme) {
        String normalized = name == null ? "default" : name.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "default" -> new MermaidRenderer(theme);
            case "gitgraph" -> new GitGraphMermaidRenderer(theme);
            case "conditional" -> new ConditionalBranchMermaidRenderer(new MermaidRenderer(theme), theme);
            case "metro", "metro-map", "metromap" -> new MetroMapMermaidRenderer(theme);
            default -> throw new IllegalArgumentException(
                    "Invalid --renderer value '" + name
                            + "'. Allowed: default, gitgraph, conditional, metro, metro-map, metromap");
        };
    }
}
