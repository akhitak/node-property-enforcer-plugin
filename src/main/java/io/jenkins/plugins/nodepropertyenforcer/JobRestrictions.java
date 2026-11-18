package io.jenkins.plugins.nodepropertyenforcer;

import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.nodes.JobRestrictionProperty;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.JobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.job.RegexNameRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.job.StartedByMemberOfGroupRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.job.StartedByUserRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.logic.AndJobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.logic.AnyJobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.logic.MultipleAndJobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.logic.MultipleOrJobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.logic.NotJobRestriction;
import com.synopsys.arc.jenkinsci.plugins.jobrestrictions.restrictions.logic.OrJobRestriction;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.slaves.NodeProperty;
import io.jenkins.plugins.jobrestrictions.restrictions.job.JobClassNameRestriction;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

@Extension(optional = true)
public final class JobRestrictions implements PropertyRequirement {
    static final String MESSAGE = "Choose a job restriction other than ‘No restriction (take any)’.";
    static final String LIST = "Add at least one restriction and complete its settings.";
    static final String USER = "Enter a user ID or remove this empty row.";
    static final String GROUP = "Enter a group ID or remove this empty row.";
    static final String LIMIT = "Use at most 32 nested levels and 100 entries per restriction list.";
    static final String COMPLEXITY = "Use fewer nested restrictions or list entries.";
    private static final String FIRST = "first";
    private static final String SECOND = "second";
    private static final String RESTRICTIONS = "restrictions";

    private static final class Budget {
        private int remaining = 1024;

        void use() {
            if (--remaining < 0) throw new TooComplex();
        }
    }

    private static final class TooComplex extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TooComplex() {
            super(COMPLEXITY, null, false, false);
        }
    }

    private enum Kind {
        ANY,
        BINARY,
        NOT,
        MULTIPLE,
        EXPRESSION,
        CLASSES,
        USERS,
        GROUPS,
        CUSTOM
    }

    private static final Map<Class<?>, Kind> KINDS = Map.of(
            AnyJobRestriction.class,
            Kind.ANY,
            AndJobRestriction.class,
            Kind.BINARY,
            OrJobRestriction.class,
            Kind.BINARY,
            NotJobRestriction.class,
            Kind.NOT,
            MultipleAndJobRestriction.class,
            Kind.MULTIPLE,
            MultipleOrJobRestriction.class,
            Kind.MULTIPLE,
            RegexNameRestriction.class,
            Kind.EXPRESSION,
            JobClassNameRestriction.class,
            Kind.CLASSES,
            StartedByUserRestriction.class,
            Kind.USERS,
            StartedByMemberOfGroupRestriction.class,
            Kind.GROUPS);

    private static final Map<Class<?>, Function<JobRestriction, List<JobRestriction>>> CHILDREN = Map.of(
            RegexNameRestriction.class, r -> List.of(),
            AnyJobRestriction.class, r -> List.of(),
            AndJobRestriction.class,
                    r -> java.util.Arrays.asList(
                            ((AndJobRestriction) r).getFirst(), ((AndJobRestriction) r).getSecond()),
            OrJobRestriction.class,
                    r -> java.util.Arrays.asList(((OrJobRestriction) r).getFirst(), ((OrJobRestriction) r).getSecond()),
            NotJobRestriction.class, r -> java.util.Collections.singletonList(((NotJobRestriction) r).getRestriction()),
            MultipleAndJobRestriction.class, r -> ((MultipleAndJobRestriction) r).getRestrictions(),
            MultipleOrJobRestriction.class, r -> ((MultipleOrJobRestriction) r).getRestrictions());

    @Override
    public String queueReason(Node node, Queue.BuildableItem item) {
        var property = node.getNodeProperties().get(JobRestrictionProperty.class);
        if (property == null || !mismatch(property, item)) return null;
        return describe(node.hasPermission(Computer.CONFIGURE) ? property.getJobRestriction() : null, item);
    }

    private static boolean mismatch(JobRestrictionProperty property, Queue.BuildableItem item) {
        var restriction = property.getJobRestriction();
        return nameOnly(restriction, 0, new int[] {128})
                && new JobRestrictions().check(property) == null
                && !restriction.canTake(item);
    }

    private static String describe(JobRestriction restriction, Queue.BuildableItem item) {
        String job = "Job “" + QueueMessages.shortText(((Item) item.task).getFullName()) + "”";
        if (restriction == null) return job + " does not meet this node's job-name rules.";
        if (restriction instanceof RegexNameRestriction regex) {
            return job + " does not match the required job-name pattern “"
                    + QueueMessages.shortText(regex.getRegexExpression()) + "”.";
        }
        return combinedReason(restriction, job);
    }

    private static String combinedReason(JobRestriction restriction, String job) {
        if (restriction instanceof NotJobRestriction not
                && not.getRestriction() instanceof RegexNameRestriction regex) {
            return job + " matches the excluded job-name pattern “"
                    + QueueMessages.shortText(regex.getRegexExpression()) + "”.";
        }
        return job + " does not meet this node's combined job-name rules. Review the conditions under Job restriction.";
    }

    // Only evaluate the provider's built-in, pure name rules. Never invoke arbitrary third-party restrictions.
    static boolean nameOnly(JobRestriction restriction, int depth, int[] budget) {
        if (--budget[0] < 0 || depth > 32) return false;
        return restriction != null && knownChildren(restriction, depth, budget);
    }

    private static boolean knownChildren(JobRestriction restriction, int depth, int[] budget) {
        var read = CHILDREN.get(restriction.getClass());
        return read != null && children(read.apply(restriction), depth, budget);
    }

    private static boolean children(List<JobRestriction> restrictions, int depth, int[] budget) {
        return restrictions != null
                && restrictions.size() <= 100
                && restrictions.stream().allMatch(r -> nameOnly(r, depth + 1, budget));
    }

    @Override
    public String propertyId() {
        return JobRestrictionProperty.class.getName();
    }

    @Override
    public String check(NodeProperty<?> property) {
        try {
            return checked(data(((JobRestrictionProperty) property).getJobRestriction(), 0, new Budget()));
        } catch (TooComplex e) {
            return COMPLEXITY;
        }
    }

    @Override
    public String checkSubmitted(JSONObject property, boolean creating) {
        return checked(property.optJSONObject("jobRestriction"));
    }

    private static String checked(JSONObject value) {
        try {
            return validate(value, 0, new Budget());
        } catch (TooComplex e) {
            return COMPLEXITY;
        }
    }

    private static String validate(JSONObject value, int depth, Budget budget) {
        budget.use();
        if (depth > 32) return LIMIT;
        Class<?> type = type(value);
        if (type == null || type == AnyJobRestriction.class) return MESSAGE;
        return content(KINDS.getOrDefault(type, Kind.CUSTOM), value, depth, budget);
    }

    private static Class<?> type(JSONObject value) {
        if (value == null || value.isNullObject()) return null;
        String type = value.optString("stapler-class", value.optString("$class", ""));
        if (!value.optString("$class", type).equals(type)) return null;
        return JobRestriction.all().stream()
                .filter(d -> d.clazz.getName().equals(type))
                .map(d -> d.clazz)
                .findFirst()
                .orElse(null);
    }

    private static String content(Kind kind, JSONObject value, int depth, Budget budget) {
        return switch (kind) {
            case BINARY -> pair(value, depth, budget);
            case NOT -> validate(value.optJSONObject("restriction"), depth + 1, budget);
            case MULTIPLE -> entries(value.opt(RESTRICTIONS), LIST, row -> validate(row, depth + 1, budget), budget);
            default -> leafContent(kind, value, budget);
        };
    }

    private static String leafContent(Kind kind, JSONObject value, Budget budget) {
        return switch (kind) {
            case EXPRESSION -> RestrictionValues.checkExpression(string(value, "regexExpression"));
            case CLASSES ->
                entries(
                        value.opt("jobClasses"),
                        "Add at least one job class.",
                        row -> RestrictionValues.className(string(row, "selectedClass")),
                        budget);
            case USERS ->
                entries(
                        value.opt("usersList"),
                        "Add at least one user ID.",
                        row -> required(row, "selectedUserId", USER),
                        budget);
            case GROUPS ->
                entries(
                        value.opt("groupList"),
                        "Add at least one group ID.",
                        row -> required(row, "selectedGroupId", GROUP),
                        budget);
            default -> null; // Other plugins own their registered restrictions' semantics.
        };
    }

    private static String pair(JSONObject value, int depth, Budget budget) {
        String first = validate(value.optJSONObject(FIRST), depth + 1, budget);
        return first != null ? first : validate(value.optJSONObject(SECOND), depth + 1, budget);
    }

    private static String entries(Object value, String empty, Function<JSONObject, String> check, Budget budget) {
        List<?> rows = value instanceof JSONArray array ? array : Collections.singletonList(value);
        if (rows.isEmpty() || value == null) return empty;
        if (rows.size() > 100) return LIMIT;
        return rows.stream()
                .map(row -> entryProblem(row, empty, check, budget))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static String entryProblem(Object row, String empty, Function<JSONObject, String> check, Budget budget) {
        budget.use();
        if (!(row instanceof JSONObject object) || object.isNullObject()) return empty;
        return check.apply(object);
    }

    private static String required(JSONObject row, String field, String message) {
        String value = string(row, field);
        return value == null || value.isBlank() ? message : null;
    }

    private static String string(JSONObject row, String field) {
        return row != null && row.opt(field) instanceof String text ? text : null;
    }

    /** Reads only public provider models; submitted objects are never instantiated here. */
    private static JSONObject data(JobRestriction restriction, int depth, Budget budget) {
        budget.use();
        if (restriction == null) return new JSONObject();
        var value =
                new JSONObject().element("stapler-class", restriction.getClass().getName());
        if (depth > 32) return value;
        logicData(restriction, depth, value, budget);
        return value;
    }

    private static void logicData(JobRestriction restriction, int depth, JSONObject value, Budget budget) {
        switch (restriction) {
            case AndJobRestriction r ->
                value.element(FIRST, data(r.getFirst(), depth + 1, budget))
                        .element(SECOND, data(r.getSecond(), depth + 1, budget));
            case OrJobRestriction r ->
                value.element(FIRST, data(r.getFirst(), depth + 1, budget))
                        .element(SECOND, data(r.getSecond(), depth + 1, budget));
            case NotJobRestriction r -> value.element("restriction", data(r.getRestriction(), depth + 1, budget));
            default -> listData(restriction, depth, value, budget);
        }
    }

    private static void listData(JobRestriction restriction, int depth, JSONObject value, Budget budget) {
        switch (restriction) {
            case MultipleAndJobRestriction r ->
                value.element(RESTRICTIONS, rows(r.getRestrictions(), child -> data(child, depth + 1, budget), budget));
            case MultipleOrJobRestriction r ->
                value.element(RESTRICTIONS, rows(r.getRestrictions(), child -> data(child, depth + 1, budget), budget));
            default -> leafData(restriction, value, budget);
        }
    }

    private static void leafData(JobRestriction restriction, JSONObject value, Budget budget) {
        switch (restriction) {
            case RegexNameRestriction r -> value.element("regexExpression", r.getRegexExpression());
            case JobClassNameRestriction r ->
                value.element(
                        "jobClasses",
                        rows(
                                r.getJobClasses(),
                                row -> new JSONObject().element("selectedClass", row.getSelectedClass()),
                                budget));
            case StartedByUserRestriction r ->
                value.element(
                        "usersList",
                        rows(
                                r.getUsersList(),
                                row -> new JSONObject().element("selectedUserId", row.getSelectedUserId()),
                                budget));
            case StartedByMemberOfGroupRestriction r ->
                value.element(
                        "groupList",
                        rows(
                                r.getGroupList(),
                                row -> new JSONObject().element("selectedGroupId", row.getSelectedGroupId()),
                                budget));
            default -> {
                /* External restriction types retain their own validation. */
            }
        }
    }

    private static <T> List<JSONObject> rows(List<T> entries, Function<T, JSONObject> convert, Budget budget) {
        if (entries == null) return List.of();
        return entries.stream()
                .limit(101)
                .map(entry -> rowData(entry, convert, budget))
                .toList();
    }

    private static <T> JSONObject rowData(T entry, Function<T, JSONObject> convert, Budget budget) {
        budget.use();
        return entry == null ? new JSONObject() : convert.apply(entry);
    }

    @Override
    public void describe(JSONObject rule) {
        rule.element("check", "jobs")
                .element("contentMessage", MESSAGE)
                .element("unrestricted", AnyJobRestriction.class.getName())
                .element(
                        "restrictionTypes",
                        JobRestriction.all().stream()
                                .map(d -> d.clazz.getName())
                                .toList())
                .element("listMessage", LIST)
                .element("userMessage", USER)
                .element("groupMessage", GROUP)
                .element("classMessage", RestrictionValues.CLASS_NAME)
                .element("limitMessage", LIMIT)
                .element(
                        "restrictionLabels",
                        JobRestriction.all().stream()
                                .map(d -> d.getDisplayName())
                                .toList())
                .element(
                        "restrictionKinds",
                        KINDS.entrySet().stream()
                                .collect(java.util.stream.Collectors.toMap(
                                        entry -> entry.getKey().getName(),
                                        entry -> entry.getValue().name().toLowerCase(Locale.ROOT))));
    }
}
