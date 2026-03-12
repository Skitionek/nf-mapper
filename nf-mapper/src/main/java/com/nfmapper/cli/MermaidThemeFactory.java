package com.nfmapper.cli;

import java.util.Locale;

import com.nfmapper.mermaid.MermaidTheme;
import com.nfmapper.mermaid.NfCoreMermaidTheme;
import com.nfmapper.mermaid.PlainMermaidTheme;

final class MermaidThemeFactory {
    MermaidTheme create(String name) {
        String normalized = name == null ? "nf-core" : name.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "nf-core", "nfcore", "base", "default" -> new NfCoreMermaidTheme();
            case "plain", "mermaid-default" -> new PlainMermaidTheme();
            default -> throw new IllegalArgumentException(
                    "Invalid --theme value '" + name + "'. Allowed: nf-core, plain");
        };
    }
}
