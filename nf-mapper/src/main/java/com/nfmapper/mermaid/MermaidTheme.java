package com.nfmapper.mermaid;

import java.util.Map;

public interface MermaidTheme {
    String mermaidThemeName();

    Map<String, Object> themeVariables();
}
