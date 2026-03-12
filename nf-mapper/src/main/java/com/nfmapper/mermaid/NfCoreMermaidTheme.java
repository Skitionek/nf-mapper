package com.nfmapper.mermaid;

import java.util.LinkedHashMap;
import java.util.Map;

public class NfCoreMermaidTheme implements MermaidTheme {

    private static final Map<String, Object> THEME_VARS;
    static {
        THEME_VARS = new LinkedHashMap<>();
        THEME_VARS.put("git0", "#24B064");
        THEME_VARS.put("gitInv0", "#ffffff");
        THEME_VARS.put("git1", "#FA7F19");
        THEME_VARS.put("gitInv1", "#ffffff");
        THEME_VARS.put("git2", "#0570b0");
        THEME_VARS.put("gitInv2", "#ffffff");
        THEME_VARS.put("git3", "#e63946");
        THEME_VARS.put("gitInv3", "#ffffff");
        THEME_VARS.put("git4", "#9b59b6");
        THEME_VARS.put("gitInv4", "#ffffff");
        THEME_VARS.put("git5", "#f5c542");
        THEME_VARS.put("gitInv5", "#000000");
        THEME_VARS.put("git6", "#1abc9c");
        THEME_VARS.put("gitInv6", "#ffffff");
        THEME_VARS.put("git7", "#7b2d3b");
        THEME_VARS.put("gitInv7", "#ffffff");
    }

    @Override
    public String mermaidThemeName() {
        return "base";
    }

    @Override
    public Map<String, Object> themeVariables() {
        return new LinkedHashMap<>(THEME_VARS);
    }
}
