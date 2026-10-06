package io.hl7sender.core.hl7;

import ca.uhn.hl7v2.model.Composite;
import ca.uhn.hl7v2.model.Group;
import ca.uhn.hl7v2.model.Type;
import ca.uhn.hl7v2.parser.DefaultModelClassFactory;
import ca.uhn.hl7v2.parser.ModelClassFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Field and component names for HL7 v2 segments, taken from the HAPI structure definitions of the
 * message's version. For example, PID-5 is "Patient Name" (XPN) and PID-5.1 is "Family Name". This is
 * used for editor tooltips and the structure tree. Unknown versions fall back to v2.5.1. Z-segments and
 * other unknown segments have no names.
 *
 * <p>Thread-safe. Results are cached per version and segment.
 */
public final class FieldDictionary {

    /** Version used when a message's own version has no HAPI structures. */
    public static final String FALLBACK_VERSION = "2.5.1";

    private static final FieldDictionary SHARED = new FieldDictionary();
    private static final Pattern COMPONENT_GETTER = Pattern.compile("^get[A-Za-z]+?(\\d+)_(\\w+)$");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    /**
     * One field of a segment.
     *
     * @param name       e.g. {@code Patient Name}
     * @param dataType   e.g. {@code XPN}
     * @param components component names by position (index 0 = component 1), empty for primitive types
     */
    public record FieldInfo(String name, String dataType, List<String> components) {

        /** Name of component {@code c} (1-based), if known. */
        public Optional<String> component(int c) {
            return c >= 1 && c <= components.size() ? Optional.of(components.get(c - 1)) : Optional.empty();
        }
    }

    private final ModelClassFactory factory = new DefaultModelClassFactory();
    private final Map<String, Optional<List<FieldInfo>>> cache = new ConcurrentHashMap<>();

    private FieldDictionary() {
    }

    public static FieldDictionary shared() {
        return SHARED;
    }

    /** Field {@code field} (1-based) of {@code segment} in {@code version}, if HAPI knows it. */
    public Optional<FieldInfo> field(String version, String segment, int field) {
        return segment(version, segment).filter(list -> field >= 1 && field <= list.size())
                .map(list -> list.get(field - 1));
    }

    /**
     * A human-readable label such as {@code PID-5.1 Patient Name > Family Name (XPN)}.
     *
     * @param component 1-based component, or 0 for the whole field
     */
    public String describe(String version, String segment, int field, int component) {
        String location = segment + "-" + field + (component > 0 ? "." + component : "");
        Optional<FieldInfo> info = field(version, segment, field);
        if (info.isEmpty()) {
            return location;
        }
        FieldInfo f = info.get();
        StringBuilder sb = new StringBuilder(location).append(' ').append(f.name());
        if (component > 0) {
            f.component(component).ifPresent(c -> sb.append(" > ").append(c));
        }
        sb.append(" (").append(f.dataType()).append(')');
        return sb.toString();
    }

    private Optional<List<FieldInfo>> segment(String version, String segment) {
        String v = supported(version) ? version : FALLBACK_VERSION;
        return cache.computeIfAbsent(v + "/" + segment, key -> load(v, segment));
    }

    private Optional<List<FieldInfo>> load(String version, String segmentName) {
        if (segmentName == null || !segmentName.matches("[A-Z][A-Z0-9]{2}")) {
            return Optional.empty();
        }
        try {
            Class<? extends ca.uhn.hl7v2.model.Segment> cls = factory.getSegmentClass(segmentName, version);
            if (cls == null) {
                return Optional.empty();
            }
            ca.uhn.hl7v2.model.Segment seg = cls.getConstructor(Group.class, ModelClassFactory.class)
                    .newInstance(genericMessage(version), factory);
            String[] names = seg.getNames();
            List<FieldInfo> fields = new ArrayList<>(names.length);
            for (int i = 0; i < names.length; i++) {
                Type type = seg.getField(i + 1, 0);
                fields.add(new FieldInfo(names[i], type.getName(),
                        type instanceof Composite ? componentNames(type.getClass()) : List.of()));
            }
            return Optional.of(List.copyOf(fields));
        } catch (ReflectiveOperationException | ca.uhn.hl7v2.HL7Exception | RuntimeException e) {
            return Optional.empty();
        }
    }

    private Group genericMessage(String version) throws ReflectiveOperationException {
        String cls = "ca.uhn.hl7v2.model.GenericMessage$V" + version.replace(".", "");
        return (Group) Class.forName(cls).getConstructor(ModelClassFactory.class).newInstance(factory);
    }

    static List<String> componentNames(Class<?> compositeType) {
        TreeMap<Integer, String> byIndex = new TreeMap<>();
        for (Method m : compositeType.getMethods()) {
            Matcher matcher = COMPONENT_GETTER.matcher(m.getName());
            if (m.getParameterCount() == 0 && matcher.matches()) {
                byIndex.put(Integer.parseInt(matcher.group(1)), humanize(matcher.group(2)));
            }
        }
        if (byIndex.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= byIndex.lastKey(); i++) {
            out.add(byIndex.getOrDefault(i, ""));
        }
        return List.copyOf(out);
    }

    static String humanize(String camel) {
        return String.join(" ", CAMEL.split(camel.replace('_', ' '))).replaceAll("\\s+", " ").trim();
    }

    private static boolean supported(String version) {
        if (version == null || !version.matches("\\d\\.\\d(\\.\\d)?")) {
            return false;
        }
        try {
            Class.forName("ca.uhn.hl7v2.model.GenericMessage$V" + version.replace(".", ""));
            Class.forName("ca.uhn.hl7v2.model.v" + version.replace(".", "") + ".segment.MSH");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
