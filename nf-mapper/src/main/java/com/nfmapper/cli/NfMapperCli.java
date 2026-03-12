package com.nfmapper.cli;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nfmapper.mermaid.MermaidRenderer;
import com.nfmapper.mermaid.MermaidTheme;
import com.nfmapper.model.ParsedPipeline;
import com.nfmapper.parser.NextflowParser;

import groovy.json.JsonSlurper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "nf-mapper", description = "Convert a Nextflow pipeline (.nf) into a Mermaid diagram.", mixinStandardHelpOptions = true)
public class NfMapperCli implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "PIPELINE.NF", description = "Path to the Nextflow pipeline file. Not required with --regenerate.")
    private Path input;

    @Option(names = { "-o",
            "--output" }, paramLabel = "FILE", description = "Write the diagram to FILE instead of stdout.")
    private Path output;

    @Option(names = "--update", paramLabel = "FILE", description = "Update diagram inside marker blocks in FILE.")
    private Path update;

    @Option(names = "--regenerate", paramLabel = "FILE", description = "Scan FILE for nf-mapper blocks with pipeline= attribute and regenerate.")
    private Path regenerate;

    @Option(names = "--marker", defaultValue = "nf-mapper", paramLabel = "NAME", description = "Marker name for --update (default: nf-mapper).")
    private String marker;

    @Option(names = "--title", paramLabel = "TEXT", description = "Optional diagram title.")
    private String title;

    @Option(names = "--format", defaultValue = "plain", paramLabel = "FORMAT", description = "Output format: plain or md (default: plain).")
    private String format;

    @Option(names = "--config", paramLabel = "JSON", description = "JSON config overrides for Mermaid rendering.")
    private String configJson;

    @Option(names = "--renderer", defaultValue = "default", paramLabel = "NAME", description = "Renderer strategy: default or conditional (default: default).")
    private String rendererMode;

    @Option(names = "--theme", defaultValue = "nf-core", paramLabel = "NAME", description = "Theme class: nf-core or plain (default: nf-core).")
    private String themeMode;

    private PrintStream out = System.out;
    private PrintStream err = System.err;

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        NfMapperCli cmd = new NfMapperCli();
        cmd.out = out;
        cmd.err = err;
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(out, true));
        cl.setErr(new PrintWriter(err, true));
        return cl.execute(args);
    }

    @Override
    public Integer call() throws Exception {
        if (regenerate != null) {
            int errors = regenerateAll(regenerate.toString());
            return errors > 0 ? 1 : 0;
        }

        if (input == null) {
            err.println("nf-mapper: error: PIPELINE.NF is required unless --regenerate is used");
            return 1;
        }

        Map<String, Object> configMap = parseConfigJson(configJson);
        if (configMap == null && configJson != null)
            return 1; // parse error already printed

        NextflowParser parser = new NextflowParser();
        ParsedPipeline pipeline;
        try {
            pipeline = parser.parseFile(input.toString());
        } catch (IOException e) {
            err.println("nf-mapper: error: cannot read file '" + input + "': " + e.getMessage());
            return 1;
        }

        String diagram = renderPipeline(pipeline, title, configMap, rendererMode, themeMode);

        String outputContent = "md".equals(format) ? "```mermaid\n" + diagram + "\n```" : diagram;

        if (update != null) {
            try {
                updateMarker(update.toString(), outputContent, marker);
            } catch (Exception e) {
                err.println("nf-mapper: error: " + e.getMessage());
                return 1;
            }
        } else if (output != null) {
            Files.writeString(output, outputContent + "\n");
        } else {
            out.println(outputContent);
        }

        return 0;
    }

    // -------------------------------------------------------------------------
    // Config JSON parsing
    // -------------------------------------------------------------------------

    private Map<String, Object> parseConfigJson(String json) {
        if (json == null)
            return null;
        try {
            JsonSlurper slurper = new JsonSlurper();
            Object parsed = slurper.parseText(json);
            return toStringKeyedMap(parsed);
        } catch (IllegalArgumentException e) {
            err.println("nf-mapper: error: --config must be a JSON object");
            return null;
        } catch (Exception e) {
            err.println("nf-mapper: error: --config is not valid JSON: " + e.getMessage());
            return null;
        }
    }

    private Map<String, Object> toStringKeyedMap(Object parsed) {
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("JSON value is not an object");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("JSON object contains a non-string key");
            }
            normalized.put(key, entry.getValue());
        }
        return normalized;
    }

    // -------------------------------------------------------------------------
    // Marker update
    // -------------------------------------------------------------------------

    private static final Pattern BLOCK_RE = Pattern.compile(
            "(<!--\\s*nf-mapper(?::[\\w-]+)?[^>]*-->)(.*?)(<!--\\s*/nf-mapper(?::[\\w-]+)?\\s*-->)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private void updateMarker(String filepath, String content, String markerName) throws IOException {
        String text = Files.readString(Path.of(filepath));
        String startPat = Pattern.quote("<!-- " + markerName) + "[^>]*-->";
        String endStr = "<!-- /" + markerName + " -->";
        Pattern pattern = Pattern.compile(
                "(" + startPat + ").*?" + Pattern.quote(endStr),
                Pattern.DOTALL);
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            throw new IOException("Markers not found in '" + filepath + "'. " +
                    "Add '<!-- " + markerName + " -->' and '<!-- /" + markerName + " -->' to the file.");
        }
        String newText = m.replaceAll(mr -> mr.group(1) + "\n" + Matcher.quoteReplacement(content) + "\n" + endStr);
        Files.writeString(Path.of(filepath), newText);
    }

    int regenerateAll(String filepath) throws IOException {
        String raw = Files.readString(Path.of(filepath));
        String baseDir = Path.of(filepath).toAbsolutePath().getParent().toString();

        // Mask fenced code blocks
        Pattern fenceRe = Pattern.compile("```[^\\n]*\\n.*?```", Pattern.DOTALL);
        StringBuffer masked = new StringBuffer(raw.length());
        Matcher fm = fenceRe.matcher(raw);
        while (fm.find()) {
            String replacement = "\0".repeat(fm.group().length());
            fm.appendReplacement(masked, Matcher.quoteReplacement(replacement));
        }
        fm.appendTail(masked);
        String maskedText = masked.toString();

        List<String> segments = new ArrayList<>();
        int lastEnd = 0;
        int[] errors = { 0 };

        Matcher m = BLOCK_RE.matcher(maskedText);
        while (m.find()) {
            segments.add(raw.substring(lastEnd, m.start()));

            String opening = raw.substring(m.start(1), m.end(1));
            String closing = raw.substring(m.start(3), m.end(3));

            Map<String, String> attrs = parseMarkerAttrs(opening);
            String pipelinePath = attrs.get("pipeline");
            if (pipelinePath == null) {
                segments.add(raw.substring(m.start(), m.end()));
                lastEnd = m.end();
                continue;
            }

            if (!Path.of(pipelinePath).isAbsolute()) {
                pipelinePath = baseDir + File.separator + pipelinePath;
            }

            String blockTitle = attrs.getOrDefault("title", null);
            String fmt = attrs.getOrDefault("format", "md");
            String configStr = attrs.get("config");
            String renderer = attrs.getOrDefault("renderer", rendererMode);
            String theme = attrs.getOrDefault("theme", themeMode);

            Map<String, Object> configMap = null;
            if (configStr != null) {
                try {
                    JsonSlurper slurper = new JsonSlurper();
                    Object parsed = slurper.parseText(configStr);
                    configMap = toStringKeyedMap(parsed);
                } catch (IllegalArgumentException e) {
                    err.println(
                            "nf-mapper: error: config attribute in marker must be a JSON object");
                    errors[0]++;
                    segments.add(raw.substring(m.start(), m.end()));
                    lastEnd = m.end();
                    continue;
                } catch (Exception e) {
                    err.println("nf-mapper: error parsing config in marker: " + e.getMessage());
                    errors[0]++;
                    segments.add(raw.substring(m.start(), m.end()));
                    lastEnd = m.end();
                    continue;
                }
            }

            try {
                NextflowParser parser = new NextflowParser();
                ParsedPipeline pipeline = parser.parseFile(pipelinePath);
                String diagram = renderPipeline(pipeline, blockTitle, configMap, renderer, theme);
                String body = "md".equals(fmt) ? "```mermaid\n" + diagram + "\n```" : diagram;
                segments.add(opening + "\n" + body + "\n" + closing);
            } catch (Exception e) {
                err.println("nf-mapper: error processing '" + pipelinePath + "': " + e.getMessage());
                errors[0]++;
                segments.add(raw.substring(m.start(), m.end()));
            }
            lastEnd = m.end();
        }

        segments.add(raw.substring(lastEnd));
        String newText = String.join("", segments);
        if (!newText.equals(raw)) {
            Files.writeString(Path.of(filepath), newText);
        }
        return errors[0];
    }

    private Map<String, String> parseMarkerAttrs(String opening) {
        Map<String, String> result = new LinkedHashMap<>();
        // Extract attrs portion from <!-- nf-mapper[:name]? ATTRS -->
        Pattern attrsPat = Pattern.compile("<!--\\s*nf-mapper(?:[:\\w-]+)?\\s*(.*?)\\s*-->",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher am = attrsPat.matcher(opening);
        if (!am.find())
            return result;
        String attrsStr = am.group(1);
        Pattern kvPat = Pattern.compile("([\\w-]+)=(?:\"([^\"]*)\"|'([^']*)')");
        Matcher kvm = kvPat.matcher(attrsStr);
        while (kvm.find()) {
            String key = kvm.group(1);
            String value = kvm.group(2) != null ? kvm.group(2) : kvm.group(3);
            result.put(key, value);
        }
        return result;
    }

    private String renderPipeline(ParsedPipeline pipeline,
            String diagramTitle,
            Map<String, Object> configMap,
            String rendererName,
            String themeName) {
        try {
            MermaidTheme theme = new MermaidThemeFactory().create(themeName);
            MermaidRenderer renderer = new MermaidRendererFactory().create(rendererName, theme);
            return renderer.render(pipeline, diagramTitle, configMap);
        } catch (IllegalArgumentException ex) {
            throw new CommandLine.ParameterException(new CommandLine(this), ex.getMessage());
        }
    }
}
