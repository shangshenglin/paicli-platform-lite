package com.paicli.platform.server.knowledge;

import org.springframework.stereotype.Component;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Structure-aware document chunking without an external parser service. */
@Component
public class StructuredDocumentChunker {
    static final int TARGET_CHARS = 1_600;
    static final int MAX_CHARS = 2_200;
    static final int OVERLAP_CHARS = 180;
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+){0,5})[、.\\s]+([^。；;]{2,100})\\s*$");
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*+]\\s+|\\d+[.)、]\\s+).+");

    public List<Chunk> chunk(String content) {
        if (content == null || content.isBlank()) return List.of();
        List<Block> blocks = blocks(content);
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int start = -1;
        int end = 0;
        String heading = "";
        String kind = "paragraph";
        for (Block original : blocks) {
            for (Block block : splitLarge(original)) {
                boolean sectionChanged = !heading.isBlank() && !block.heading().equals(heading);
                boolean overflow = current.length() > 0
                        && current.length() + block.text().length() + 2 > TARGET_CHARS;
                if (current.length() > 0 && (sectionChanged
                        || (overflow && current.length() >= TARGET_CHARS / 3))) {
                    add(chunks, start, end, heading, kind, current.toString());
                    String overlap = semanticTail(current.toString());
                    current.setLength(0);
                    if (!sectionChanged && !overlap.isBlank()) current.append(overlap).append("\n");
                    start = sectionChanged || overlap.isBlank() ? block.start() : Math.max(start, end - overlap.length());
                }
                if (start < 0) start = block.start();
                if (!current.isEmpty()) current.append("\n\n");
                current.append(block.text());
                end = block.end();
                heading = block.heading();
                kind = mergeKind(kind, block.kind());
                if (current.length() >= MAX_CHARS) {
                    add(chunks, start, end, heading, kind, current.toString());
                    current.setLength(0);
                    start = -1;
                    kind = "paragraph";
                }
            }
        }
        if (!current.isEmpty()) add(chunks, start, end, heading, kind, current.toString());
        return chunks;
    }

    private static List<Block> blocks(String content) {
        List<Block> values = new ArrayList<>();
        String[] lines = content.split("(?<=\\n)", -1);
        String[] headings = new String[6];
        StringBuilder buffer = new StringBuilder();
        int bufferStart = 0;
        int offset = 0;
        String bufferKind = "paragraph";
        boolean inCode = false;
        for (String raw : lines) {
            String line = raw.replaceFirst("[\\r\\n]+$", "");
            String trimmed = line.trim();
            Matcher markdown = MARKDOWN_HEADING.matcher(line);
            Matcher numbered = NUMBERED_HEADING.matcher(line);
            if (!inCode && (markdown.matches() || numbered.matches())) {
                flush(values, buffer, bufferStart, offset, headingPath(headings), bufferKind);
                int level = markdown.matches() ? markdown.group(1).length() : numbered.group(1).split("\\.").length;
                String title = markdown.matches() ? markdown.group(2).trim()
                        : numbered.group(1) + " " + numbered.group(2).trim();
                headings[Math.min(5, level - 1)] = title;
                for (int i = level; i < headings.length; i++) headings[i] = null;
                values.add(new Block(offset, offset + line.length(), headingPath(headings), "heading", line.trim()));
                bufferStart = offset + raw.length();
                bufferKind = "paragraph";
            } else if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                if (!inCode) {
                    flush(values, buffer, bufferStart, offset, headingPath(headings), bufferKind);
                    bufferStart = offset;
                    bufferKind = "code";
                }
                buffer.append(line).append('\n');
                inCode = !inCode;
                if (!inCode) {
                    flush(values, buffer, bufferStart, offset + raw.length(), headingPath(headings), "code");
                    bufferStart = offset + raw.length();
                    bufferKind = "paragraph";
                }
            } else if (!inCode && trimmed.isBlank()) {
                flush(values, buffer, bufferStart, offset, headingPath(headings), bufferKind);
                bufferStart = offset + raw.length();
                bufferKind = "paragraph";
            } else {
                String lineKind = inCode ? "code" : classify(trimmed);
                if (!buffer.isEmpty() && !lineKind.equals(bufferKind)
                        && ("table".equals(lineKind) || "table".equals(bufferKind)
                        || "code".equals(lineKind) || "code".equals(bufferKind))) {
                    flush(values, buffer, bufferStart, offset, headingPath(headings), bufferKind);
                    bufferStart = offset;
                }
                bufferKind = lineKind;
                buffer.append(line).append('\n');
            }
            offset += raw.length();
        }
        flush(values, buffer, bufferStart, content.length(), headingPath(headings), bufferKind);
        return values;
    }

    private static String classify(String line) {
        if ((line.startsWith("|") && line.endsWith("|")) || csvLike(line)) return "table";
        if (BULLET.matcher(line).matches()) return "list";
        return "paragraph";
    }

    private static boolean csvLike(String line) {
        if (line.length() > 500 || line.indexOf(',') < 0) return false;
        int commas = 0;
        for (int i = 0; i < line.length(); i++) if (line.charAt(i) == ',') commas++;
        return commas >= 2;
    }

    private static List<Block> splitLarge(Block block) {
        if (block.text().length() <= MAX_CHARS) return List.of(block);
        List<Block> values = new ArrayList<>();
        BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
        sentences.setText(block.text());
        int begin = sentences.first();
        StringBuilder part = new StringBuilder();
        int partStart = begin;
        for (int end = sentences.next(); end != BreakIterator.DONE; begin = end, end = sentences.next()) {
            String sentence = block.text().substring(begin, end).trim();
            if (sentence.isBlank()) continue;
            if (!part.isEmpty() && part.length() + sentence.length() + 1 > TARGET_CHARS) {
                values.add(new Block(block.start() + partStart, block.start() + begin,
                        block.heading(), block.kind(), part.toString()));
                part.setLength(0);
                partStart = begin;
            }
            if (sentence.length() > MAX_CHARS) {
                if (!part.isEmpty()) {
                    values.add(new Block(block.start() + partStart, block.start() + begin,
                            block.heading(), block.kind(), part.toString()));
                    part.setLength(0);
                }
                for (int at = 0; at < sentence.length(); at += TARGET_CHARS) {
                    int to = Math.min(sentence.length(), at + TARGET_CHARS);
                    values.add(new Block(block.start() + begin + at, block.start() + begin + to,
                            block.heading(), block.kind(), sentence.substring(at, to)));
                }
                partStart = end;
            } else {
                if (!part.isEmpty()) part.append(' ');
                part.append(sentence);
            }
        }
        if (!part.isEmpty()) values.add(new Block(block.start() + partStart, block.end(),
                block.heading(), block.kind(), part.toString()));
        return values.isEmpty() ? hardSplit(block) : values;
    }

    private static List<Block> hardSplit(Block block) {
        List<Block> values = new ArrayList<>();
        for (int at = 0; at < block.text().length(); at += TARGET_CHARS) {
            int to = Math.min(block.text().length(), at + TARGET_CHARS);
            values.add(new Block(block.start() + at, block.start() + to,
                    block.heading(), block.kind(), block.text().substring(at, to)));
        }
        return values;
    }

    private static void flush(List<Block> values, StringBuilder buffer, int start, int end,
                              String heading, String kind) {
        String text = buffer.toString().trim();
        if (!text.isBlank()) values.add(new Block(start, Math.max(start, end), heading, kind, text));
        buffer.setLength(0);
    }

    private static void add(List<Chunk> chunks, int start, int end, String heading, String kind, String text) {
        String value = text.trim();
        if (!value.isBlank()) chunks.add(new Chunk(Math.max(0, start), Math.max(start, end),
                heading == null ? "" : heading, kind, value));
    }

    private static String semanticTail(String value) {
        if (value.length() <= OVERLAP_CHARS) return value;
        int from = value.length() - OVERLAP_CHARS;
        int newline = value.indexOf('\n', from);
        int sentence = Math.max(value.indexOf('。', from), value.indexOf('.', from));
        int boundary = newline >= 0 ? newline + 1 : sentence >= 0 ? sentence + 1 : from;
        return value.substring(Math.min(boundary, value.length())).trim();
    }

    private static String headingPath(String[] headings) {
        List<String> values = new ArrayList<>();
        for (String heading : headings) if (heading != null && !heading.isBlank()) values.add(heading);
        return String.join(" > ", values);
    }

    private static String mergeKind(String current, String next) {
        if (current == null || current.equals("paragraph")) return next;
        return current.equals(next) ? current : "mixed";
    }

    private record Block(int start, int end, String heading, String kind, String text) { }
    public record Chunk(int start, int end, String heading, String kind, String text) { }
}
