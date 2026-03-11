package com.nfmapper.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NfInclude {
    private final String path;
    private final List<String> imports;

    public NfInclude(String path, List<String> imports) {
        this.path = path;
        this.imports = Collections.unmodifiableList(new ArrayList<>(imports));
    }

    public String getPath() { return path; }
    public List<String> getImports() { return imports; }

    @Override public String toString() { return "NfInclude(" + path + ")"; }
}
