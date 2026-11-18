package io.jenkins.plugins.nodepropertyenforcer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import hudson.slaves.NodeDescriptor;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/** Bounds and disambiguates JSON before using Jenkins' native JSON object representation. */
final class SubmittedProperties {
    private static final JsonFactory JSON = JsonFactory.builder()
            .streamReadConstraints(
                    StreamReadConstraints.builder().maxNestingDepth(64).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private SubmittedProperties() {}

    static String parameter(HttpServletRequest request, String key) throws IOException {
        String[] values = request.getParameterValues(key);
        if (values == null) {
            return null;
        }
        if (values.length != 1) {
            throw new IOException("Submit exactly one " + key + " parameter.");
        }
        return values[0];
    }

    static JSONObject parse(String json) throws IOException {
        if (json == null || json.length() > 1_048_576) {
            throw new IOException("A node configuration of at most 1 MiB is required.");
        }
        try (JsonParser parser = JSON.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("A JSON object is required.");
            }
            parser.skipChildren();
            if (parser.nextToken() != null) {
                throw new IOException("Trailing JSON content is not allowed.");
            }
            return JSONObject.fromObject(json);
        } catch (RuntimeException | IOException e) {
            throw new IOException("Invalid or ambiguous node configuration.", e);
        }
    }

    static String check(HttpServletRequest request, List<Requirement> rules, Class<? extends hudson.model.Node> type)
            throws IOException {
        JSONObject form = parse(parameter(request, "json"));
        JSONObject bag = form.optJSONObject("nodeProperties");
        boolean classBag = bag != null && bag.optBoolean("stapler-class-bag", false);
        Map<String, JSONObject> properties = classBag ? Map.of() : properties(form.opt("nodeProperties"));
        boolean creating = request.getPathInfo().endsWith("/doCreateItem");
        return NodePropertyPolicy.violation(
                rules,
                d -> checkProperty(classBag ? property(bag, d) : properties.get(d.clazz.getName()), d, type, creating));
    }

    private static String checkProperty(
            JSONObject property,
            hudson.slaves.NodePropertyDescriptor descriptor,
            Class<? extends hudson.model.Node> type,
            boolean creating) {
        if (!descriptor.isApplicable(type) || property == null) return NodePropertyPolicy.missing(descriptor);
        var check = PropertyRequirement.find(descriptor.getId());
        return check == null ? null : check.checkSubmitted(property, creating);
    }

    private static JSONObject property(JSONObject bag, hudson.slaves.NodePropertyDescriptor descriptor) {
        JSONObject property = bag.optJSONObject(descriptor.getJsonSafeClassName());
        String type = descriptor.clazz.getName();
        return property != null
                        && !property.isNullObject()
                        && type.equals(property.optString("stapler-class", type))
                        && type.equals(property.optString("$class", type))
                ? property
                : null;
    }

    private static Map<String, JSONObject> properties(Object value) throws IOException {
        List<?> entries = value instanceof JSONArray array ? array : java.util.Collections.singletonList(value);
        Map<String, JSONObject> properties = new LinkedHashMap<>();
        for (Object entry : entries) {
            addProperty(properties, entry);
        }
        return properties;
    }

    private static void addProperty(Map<String, JSONObject> properties, Object entry) throws IOException {
        if (entry instanceof JSONObject property && !property.isNullObject()) {
            String type = property.optString("stapler-class", "");
            if (!property.optString("$class", type).equals(type) || properties.putIfAbsent(type, property) != null) {
                throw new IOException("Ambiguous node property type.");
            }
        }
    }

    static Class<? extends hudson.model.Node> type(HttpServletRequest request) throws IOException {
        String id = parameter(request, "type");
        if (id != null && Jenkins.get().getDescriptor(id) instanceof NodeDescriptor descriptor) {
            return descriptor.clazz;
        }
        throw new IOException("Select a recognized node type.");
    }
}
