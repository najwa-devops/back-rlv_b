package com.example.releve_bancaire.evo;

import java.util.*;
import java.util.regex.Pattern;

public class RegexGenerator {
    private RegexGenerator() {
    }

    public static Builder builder() {
        return new Builder();
    }

    // ================= BUILDER =================
    public static class Builder {

        private final List<String> samples = new ArrayList<>();
        private boolean learn = false;

        public Builder addSample(String sample) {
            String normalized = normalize(sample);
            if (normalized != null) {
                samples.add(normalized);
            }
            return this;
        }

        public Builder addSamples(Collection<String> inputs) {
            if (inputs == null || inputs.isEmpty()) {
                return this;
            }
            inputs.forEach(this::addSample);
            return this;
        }

        public Builder learn() {
            this.learn = true;
            return this;
        }

        public RegexResult build() {
            if (samples.isEmpty()) {
                return new RegexResult("", 0.0);
            }

            String regex = generateRegex(samples, learn);
            double confidence = calculateConfidence(samples, regex);

            return new RegexResult(regex, confidence);
        }
    }

    // ================= CORE LOGIC =================
    private static String generateRegex(List<String> samples, boolean learn) {

        int minLength = samples.stream()
                .mapToInt(String::length)
                .min()
                .orElse(0);
        int maxLength = samples.stream()
                .mapToInt(String::length)
                .max()
                .orElse(0);

        List<Token> tokens = new ArrayList<>(maxLength);
        for (int i = 0; i < maxLength; i++) {
            List<Character> chars = new ArrayList<>(samples.size());
            for (String s : samples) {
                if (i < s.length()) {
                    chars.add(s.charAt(i));
                }
            }
            if (chars.isEmpty()) {
                continue;
            }
            String token = classify(chars, learn);
            boolean optional = i >= minLength;
            tokens.add(new Token(token, optional));
        }

        String core = compressTokens(tokens);
        return "^" + core + "$";
    }

    private static String classify(List<Character> chars, boolean learn) {
        Set<Character> unique = new LinkedHashSet<>(chars);

        boolean allDigits = chars.stream().allMatch(Character::isDigit);
        boolean allLetters = chars.stream().allMatch(Character::isLetter);
        boolean allSpace = chars.stream().allMatch(Character::isWhitespace);
        boolean sameLiteral = unique.size() == 1;

        if (allDigits)
            return "\\d";
        if (allLetters)
            return "[A-Za-z]";
        if (allSpace)
            return "\\s";
        if (sameLiteral)
            return Pattern.quote(String.valueOf(chars.get(0)));

        if (!learn) {
            return toCharClass(unique);
        }

        boolean allAlphaNum = chars.stream().allMatch(Character::isLetterOrDigit);
        if (allAlphaNum)
            return "[A-Za-z0-9]";

        boolean allPunct = chars.stream().allMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c));
        if (allPunct)
            return "[\\p{Punct}]";

        return ".";
    }

    // ================= LEARNING / COMPRESSION =================
    private static String compressTokens(List<Token> tokens) {
        if (tokens.isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        Token prev = tokens.get(0);
        int count = 1;

        for (int i = 1; i < tokens.size(); i++) {
            Token current = tokens.get(i);
            if (current.equals(prev)) {
                count++;
            } else {
                out.append(quantify(prev.token(), count, prev.optional()));
                prev = current;
                count = 1;
            }
        }
        out.append(quantify(prev.token(), count, prev.optional()));
        return out.toString();
    }

    private static String quantify(String token, int count, boolean optional) {
        if (!optional && count == 1) {
            return token;
        }
        if (optional) {
            return "(?:" + token + "){0," + count + "}";
        }
        return "(?:" + token + "){" + count + "}";
    }

    private static String toCharClass(Set<Character> chars) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (char c : chars) {
            if (c == '\\' || c == ']' || c == '^' || c == '-') {
                sb.append("\\");
            }
            sb.append(c);
        }
        sb.append("]");
        return sb.toString();
    }

    // ================= CONFIDENCE =================
    private static double calculateConfidence(List<String> samples, String regex) {
        Pattern p;
        try {
            p = Pattern.compile(regex);
        } catch (Exception ignored) {
            return 0.0;
        }

        long matches = samples.stream()
                .filter(s -> p.matcher(s).matches())
                .count();

        double avg = avgLength(samples);
        double lengthConsistency = avg == 0 ? 0 : 1.0 - (stdDev(samples) / avg);
        double matchScore = (double) matches / samples.size();
        double specificityPenalty = regex.contains(".") ? 0.15 : 0.0;

        double score = (lengthConsistency * 0.35) + (matchScore * 0.65) - specificityPenalty;
        return Math.min(1.0, Math.max(0.0, score));
    }

    private static double avgLength(List<String> samples) {
        return samples.stream().mapToInt(String::length).average().orElse(0);
    }

    private static double stdDev(List<String> samples) {
        double avg = avgLength(samples);
        double variance = samples.stream()
                .mapToDouble(s -> Math.pow(s.length() - avg, 2))
                .average()
                .orElse(0);
        return Math.sqrt(variance);
    }

    private static String normalize(String sample) {
        if (sample == null) {
            return null;
        }
        String normalized = sample.trim().replaceAll("\\s+", " ");
        return normalized.isEmpty() ? null : normalized;
    }

    private record Token(String token, boolean optional) {
    }
}
