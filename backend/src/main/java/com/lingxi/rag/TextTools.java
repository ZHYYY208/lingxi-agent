package com.lingxi.rag;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TextTools {
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？!?；;])(?:\\s*)|(?:\\n+)");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern QUESTION_PREFIX = Pattern.compile("^(请问|想了解|想知道|咨询一下|麻烦问一下|帮我查一下|帮我看看|请告诉我|告诉我)[，,：:\\s]*");
    private static final Set<String> SMALLTALK = Set.of(
            "你好", "您好", "hi", "hello", "嗨", "在吗", "在不在", "谢谢", "感谢",
            "早上好", "下午好", "晚上好", "你是谁", "你能做什么", "介绍一下你自己"
    );
    private static final Set<String> QUANTITY_WORDS = Set.of(
            "多少", "几天", "多久", "金额", "比例", "标准", "限额", "天数"
    );
    private static final Set<String> PROCESS_WORDS = Set.of(
            "怎么", "如何", "申请", "流程", "要求", "步骤", "手续"
    );

    public String clean(String input) {
        if (input == null) return "";
        String text = input.replace("\r\n", "\n").replace('\r', '\n').replace("\ufeff", "");
        text = CONTROL.matcher(text).replaceAll("");
        text = text.replaceAll("[ \\t]+\\n", "\n");
        text = text.replaceAll("\\n{4,}", "\n\n\n");
        return text.strip();
    }

    public static String normalize(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    public List<String> splitSentences(String text) {
        List<String> parts = new ArrayList<>();
        for (String part : SENTENCE_BOUNDARY.split(clean(text))) {
            if (!part.isBlank()) parts.add(part.strip());
        }
        if (parts.size() <= 1) {
            parts.clear();
            for (String line : clean(text).split("\\n")) {
                if (!line.isBlank()) parts.add(line.strip());
            }
        }
        return parts;
    }

    public List<String> chunkText(String input, int size, int overlap) {
        String text = clean(input);
        if (text.isEmpty()) return List.of();
        int chunkSize = Math.max(120, size);
        int chunkOverlap = Math.max(0, Math.min(overlap, chunkSize / 2));

        List<String> units = new ArrayList<>();
        for (String paragraph : text.split("\\n{2,}")) {
            paragraph = paragraph.strip();
            if (paragraph.isEmpty()) continue;
            for (String sentence : splitSentences(paragraph)) {
                units.addAll(splitLongUnit(sentence, chunkSize));
            }
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            String separator = current.length() > 0 && !current.toString().endsWith("\n") && !current.toString().endsWith(" ") ? "\n" : "";
            String candidate = current.length() == 0 ? unit : current + separator + unit;
            if (candidate.length() <= chunkSize) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (current.length() > 0) {
                chunks.add(current.toString().strip());
                String carry = chunkOverlap == 0 ? "" : cleanOverlap(tail(current.toString(), chunkOverlap));
                current.setLength(0);
                if (!carry.isEmpty()) current.append(carry);
                current.append(unit);
            } else {
                current.append(unit);
            }
            while (current.length() > chunkSize) {
                chunks.add(current.substring(0, chunkSize).strip());
                String next = chunkOverlap == 0
                        ? current.substring(chunkSize)
                        : current.substring(chunkSize - chunkOverlap);
                current.setLength(0);
                current.append(next);
            }
        }
        if (!current.toString().isBlank()) chunks.add(current.toString().strip());

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String chunk : chunks) {
            String normalized = chunk.strip();
            if (normalized.length() >= 2) unique.add(normalized);
        }
        return new ArrayList<>(unique);
    }

    private List<String> splitLongUnit(String unit, int size) {
        if (unit.length() <= size) return List.of(unit);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < unit.length(); i += size) {
            parts.add(unit.substring(i, Math.min(unit.length(), i + size)));
        }
        return parts;
    }

    private String cleanOverlap(String tail) {
        if (tail == null || tail.isEmpty()) return "";
        Matcher matcher = Pattern.compile("[。！？!?；;]\\s*").matcher(tail);
        if (matcher.find() && matcher.end() < tail.length()) {
            return tail.substring(matcher.end()).strip();
        }
        return tail;
    }

    private String tail(String text, int length) {
        return text.length() <= length ? text : text.substring(text.length() - length);
    }

    public List<Section> splitMarkdownSections(String input) {
        List<String> lines = clean(input).lines().toList();
        List<Section> sections = new ArrayList<>();
        String currentTitle = "";
        List<String> body = new ArrayList<>();
        boolean inCode = false;
        for (String line : lines) {
            if (line.strip().startsWith("```")) inCode = !inCode;
            Matcher heading = inCode ? null : HEADING.matcher(line);
            if (heading != null && heading.matches()) {
                if (!String.join("\n", body).isBlank()) {
                    sections.add(new Section(currentTitle, clean(String.join("\n", body))));
                }
                currentTitle = heading.group(2).strip();
                body.clear();
            } else {
                body.add(line);
            }
        }
        if (!String.join("\n", body).isBlank()) {
            sections.add(new Section(currentTitle, clean(String.join("\n", body))));
        }
        return sections;
    }

    public String snippet(String text, String query, int limit) {
        String cleaned = clean(text);
        if (cleaned.length() <= limit) return cleaned;
        int best = -1;
        String lowered = cleaned.toLowerCase(Locale.ROOT);
        for (String term : termsForSnippet(query)) {
            int position = lowered.indexOf(term);
            if (position >= 0) {
                best = position;
                break;
            }
        }
        if (best < 0) return cleaned.substring(0, limit).strip() + "…";
        int start = Math.max(0, best - limit / 3);
        int end = Math.min(cleaned.length(), start + limit);
        String prefix = start > 0 ? "…" : "";
        String suffix = end < cleaned.length() ? "…" : "";
        return prefix + cleaned.substring(start, end).strip() + suffix;
    }

    private List<String> termsForSnippet(String query) {
        List<String> result = new ArrayList<>();
        for (String part : query.split("[\\s，。！？?、；;：:]+")) {
            if (part.length() >= 2) result.add(part.toLowerCase(Locale.ROOT));
        }
        if (result.isEmpty()) result.add(query.toLowerCase(Locale.ROOT));
        return result;
    }

    public List<String> extractRelevantSentences(String text, String query, ChineseTokenizer tokenizer, int maxSentences) {
        List<String> sentences = splitSentences(text).stream().filter(s -> s.length() >= 6).toList();
        if (sentences.isEmpty()) return List.of();
        Set<String> queryTerms = new HashSet<>(tokenizer.tokenize(query));
        boolean quantityIntent = QUANTITY_WORDS.stream().anyMatch(query::contains);
        boolean processIntent = PROCESS_WORDS.stream().anyMatch(query::contains);

        record Scored(double score, int index, String sentence) {
        }
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            Set<String> terms = new HashSet<>(tokenizer.tokenize(sentence));
            if (terms.isEmpty()) continue;
            Set<String> intersection = new HashSet<>(queryTerms);
            intersection.retainAll(terms);
            double overlap = intersection.size() / (double) Math.max(1, queryTerms.size());
            double density = intersection.size() / Math.pow(Math.max(1, terms.size()), 0.35);
            double firstBonus = i == 0 ? 0.24 : (i == 1 ? 0.10 : 0.0);
            double listBonus = sentence.matches("^(\\d+[.、)]|[-*•]|第[一二三四五六七八九十]+).*") ? 0.08 : 0.0;
            double quantityBonus = quantityIntent && sentence.matches(".*\\d.*") ? 0.14 : 0.0;
            double processBonus = processIntent && containsAny(sentence, List.of(
                    "提前", "工作日", "申请", "审批", "流程", "提交", "步骤", "材料"
            )) ? 0.12 : 0.0;
            scored.add(new Scored(overlap * 1.8 + density * 0.5 + firstBonus + listBonus + quantityBonus + processBonus, i, sentence));
        }
        scored.sort((a, b) -> {
            int byScore = Double.compare(b.score, a.score);
            return byScore != 0 ? byScore : Integer.compare(a.index, b.index);
        });

        LinkedHashSet<Integer> mandatory = new LinkedHashSet<>();
        if (!scored.isEmpty() && intersects(tokenizer.tokenize(sentences.getFirst()), queryTerms)) {
            mandatory.add(0);
        }
        if (quantityIntent) {
            for (int i = 0; i < sentences.size(); i++) {
                if (!mandatory.contains(i) && sentences.get(i).matches(".*\\d.*")
                        && intersects(tokenizer.tokenize(sentences.get(i)), queryTerms)) {
                    mandatory.add(i);
                    break;
                }
            }
        }
        List<Integer> selected = new ArrayList<>(mandatory);
        for (Scored item : scored) {
            if (!selected.contains(item.index)) selected.add(item.index);
            if (selected.size() >= maxSentences) break;
        }
        selected.sort(Integer::compareTo);
        List<String> result = new ArrayList<>();
        for (int index : selected) {
            if (index < sentences.size()) result.add(sentences.get(index));
        }
        return result;
    }

    private boolean intersects(List<String> left, Set<String> right) {
        for (String value : left) {
            if (right.contains(value)) return true;
        }
        return false;
    }

    public boolean isSmalltalk(String message) {
        String normalized = normalize(message).replaceAll("^[！？!?。.,，\\s]+|[！？!?。.,，\\s]+$", "");
        if (SMALLTALK.contains(normalized)) return true;
        return normalized.length() <= 4 && (normalized.contains("你好") || normalized.contains("您好") || normalized.contains("谢谢"));
    }

    public String stripQuestionPrefix(String message) {
        return QUESTION_PREFIX.matcher(message.strip()).replaceFirst("");
    }

    public String makeTitle(String message, int maxLength) {
        String title = stripQuestionPrefix(message).replaceAll("\\s+", " ").replaceAll("^[？?。.!！\\s]+|[？?。.!！\\s]+$", "");
        if (title.isBlank()) title = "新对话";
        return title.length() > maxLength ? title.substring(0, maxLength) + "…" : title;
    }

    public String formatNumber(double value, String unit) {
        String formatted;
        if (Math.abs(value) >= 100_000_000) {
            formatted = String.format(Locale.ROOT, "%.2f 亿", value / 100_000_000);
        } else if (Math.abs(value) >= 10_000) {
            formatted = String.format(Locale.ROOT, "%,.1f 万", value / 10_000);
        } else {
            formatted = String.format(Locale.ROOT, "%,.0f", value);
        }
        return formatted + unit;
    }

    public String formatNumber(BigDecimal value, String unit) {
        return formatNumber(value.setScale(2, RoundingMode.HALF_UP).doubleValue(), unit);
    }

    public String tableToMarkdown(List<String> headers, List<List<String>> rows) {
        if (rows.isEmpty()) return "_没有查询到符合条件的数据。_";
        StringBuilder builder = new StringBuilder();
        builder.append("| ").append(String.join(" | ", headers)).append(" |\n");
        builder.append("| ").append(String.join(" | ", Collections.nCopies(headers.size(), "---"))).append(" |\n");
        for (List<String> row : rows) {
            builder.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        return builder.toString().strip();
    }

    public boolean containsAny(String text, Collection<String> words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    public record Section(String title, String text) {
    }
}
