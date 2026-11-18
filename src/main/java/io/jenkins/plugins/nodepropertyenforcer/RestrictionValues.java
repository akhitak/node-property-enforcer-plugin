package io.jenkins.plugins.nodepropertyenforcer;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Shared value checks. Never executes a submitted expression. */
final class RestrictionValues {
    static final String EXPRESSION = "Enter a job-name expression.";
    static final String INVALID_EXPRESSION = "Enter a valid Java regular expression.";
    static final String CLASS_NAME = "Enter a full job class name, for example hudson.model.FreeStyleProject.";

    private RestrictionValues() {}

    static String checkExpression(String value) {
        if (value == null || value.isBlank()) return EXPRESSION;
        if (value.length() > 4096) return "Keep the expression within 4096 characters.";
        try {
            Pattern.compile(value);
            return null;
        } catch (PatternSyntaxException e) {
            return INVALID_EXPRESSION;
        }
    }

    static String className(String value) {
        if (value == null) return CLASS_NAME;
        if (value.length() > 1024) return "Keep the job class name within 1024 characters.";
        String[] parts = value.trim().split("\\.", -1);
        return parts.length > 1 && Arrays.stream(parts).allMatch(RestrictionValues::identifier) ? null : CLASS_NAME;
    }

    private static boolean identifier(String value) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.codePointAt(0))) return false;
        return value.codePoints().skip(1).allMatch(Character::isJavaIdentifierPart);
    }
}
