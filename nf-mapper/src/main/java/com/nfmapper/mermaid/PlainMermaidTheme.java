package com.nfmapper.mermaid;

import java.util.LinkedHashMap;
import java.util.Map;

public class PlainMermaidTheme implements MermaidTheme {
    @Override
    public String mermaidThemeName() {
        return "default";
    }

    @Override
    public Map<String, Object> themeVariables() {
        return new LinkedHashMap<>();
    }
}
