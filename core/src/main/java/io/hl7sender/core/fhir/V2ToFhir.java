package io.hl7sender.core.fhir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hl7sender.core.hl7.Delimiters;
import io.hl7sender.core.hl7.MessageHeader;
import io.hl7sender.core.hl7.ParsedMessage;
import io.hl7sender.core.hl7.Segment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Converts an HL7 v2 message to a FHIR R4 transaction {@code Bundle}, following the main mappings of the HL7 v2-to-FHIR
 * implementation guide. It covers the segments most interfaces use:
 *
 * <ul>
 *   <li>PID to Patient (identifiers, name, birth date, gender, address, phone), created only if no patient with the
 *       first identifier exists (conditional create)</li>
 *   <li>PV1 to Encounter, NK1 to RelatedPerson, AL1 to AllergyIntolerance, DG1 to Condition</li>
 *   <li>OBR with its OBX segments to DiagnosticReport and Observations (ORU); ORC/OBR to ServiceRequest (ORM, OML)</li>
 * </ul>
 *
 * <p>Segments it does not map are listed in {@link Result#notes()}. Resource IDs are derived from the message
 * control ID, so converting the same message twice gives the same bundle. This is for previews, test data and
 * simple feeds, not a complete implementation of the guide.
 */
public final class V2ToFhir {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Set<String> KNOWN = Set.of("MSH", "EVN", "PID", "PD1", "PV1", "PV2", "NK1", "AL1", "DG1",
            "ORC", "OBR", "OBX", "NTE", "SFT", "TQ1");
    private static final Map<String, String> CODE_SYSTEMS = Map.of(
            "LN", "http://loinc.org",
            "SNM", "http://snomed.info/sct",
            "SCT", "http://snomed.info/sct",
            "I10", "http://hl7.org/fhir/sid/icd-10",
            "I10C", "http://hl7.org/fhir/sid/icd-10-cm",
            "I9C", "http://hl7.org/fhir/sid/icd-9-cm",
            "RXNORM", "http://www.nlm.nih.gov/research/umls/rxnorm",
            "CPT", "http://www.ama-assn.org/go/cpt",
            "UCUM", "http://unitsofmeasure.org");

    /**
     * The converted message.
     *
     * @param bundle    the transaction Bundle
     * @param resources how many resources of each type, in bundle order, e.g. {@code Patient=1, Observation=3}
     * @param notes     what was not converted, e.g. {@code Segment ZPI was not converted}
     */
    public record Result(ObjectNode bundle, Map<String, Integer> resources, List<String> notes) {

        /** The bundle as indented JSON. */
        public String json() {
            try {
                return JSON.writeValueAsString(bundle);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private final ParsedMessage message;
    private final Delimiters d;
    private final ArrayNode entries = NODES.arrayNode();
    private final Map<String, Integer> counts = new java.util.LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();
    private final String seed;
    private String patientRef;
    private String encounterRef;

    private V2ToFhir(ParsedMessage message) {
        this.message = message;
        this.d = message.delimiters();
        MessageHeader h = message.header();
        this.seed = h.controlId().isEmpty() ? h.timestamp() + h.messageType() : h.controlId();
    }

    /**
     * Converts {@code text}.
     *
     * @throws io.hl7sender.core.hl7.Hl7FormatException if it is not an HL7 v2 message
     */
    public static Result convert(String text) {
        return new V2ToFhir(ParsedMessage.parse(text)).run();
    }

    private Result run() {
        MessageHeader h = message.header();
        message.first("PID").ifPresent(this::patient);
        message.first("PV1").ifPresent(this::encounter);
        message.all("NK1").forEach(this::relatedPerson);
        message.all("AL1").forEach(this::allergy);
        message.all("DG1").forEach(this::condition);
        orders(h.messageCode());
        Set<String> skipped = new LinkedHashSet<>();
        for (Segment s : message.segments()) {
            if (!KNOWN.contains(s.name())) {
                skipped.add(s.name());
            }
        }
        skipped.forEach(s -> notes.add("Segment " + s + " was not converted"));
        if (patientRef == null) {
            notes.add("No PID segment: resources have no subject");
        }
        ObjectNode bundle = NODES.objectNode();
        bundle.put("resourceType", "Bundle");
        bundle.put("type", "transaction");
        String ts = dateTime(h.timestamp());
        if (!ts.isEmpty()) {
            bundle.put("timestamp", ts);
        }
        bundle.set("entry", entries);
        return new Result(bundle, java.util.Collections.unmodifiableMap(counts), List.copyOf(notes));
    }

    // --- Resources -----------------------------------------------------------------------------------

    private void patient(Segment pid) {
        ObjectNode p = resource("Patient");
        ArrayNode ids = NODES.arrayNode();
        String firstSystem = null;
        String firstValue = null;
        for (String rep : reps(pid.field(3))) {
            String value = comp(rep, 1);
            if (value.isEmpty()) {
                continue;
            }
            ObjectNode id = ids.addObject();
            String system = identifierSystem(comp(rep, 4));
            id.put("system", system);
            id.put("value", value);
            String type = comp(rep, 5);
            if (!type.isEmpty()) {
                id.set("type", concept("http://terminology.hl7.org/CodeSystem/v2-0203", type, ""));
            }
            if (firstValue == null) {
                firstSystem = system;
                firstValue = value;
            }
        }
        if (!ids.isEmpty()) {
            p.set("identifier", ids);
        }
        ArrayNode names = NODES.arrayNode();
        for (String rep : reps(pid.field(5))) {
            ObjectNode n = humanName(rep);
            if (n != null) {
                names.add(n);
            }
        }
        if (!names.isEmpty()) {
            p.set("name", names);
        }
        String gender = switch (pid.field(8).toUpperCase(Locale.ROOT)) {
            case "M" -> "male";
            case "F" -> "female";
            case "O", "A" -> "other";
            case "U" -> "unknown";
            default -> "";
        };
        if (!gender.isEmpty()) {
            p.put("gender", gender);
        }
        String birth = date(pid.field(7));
        if (!birth.isEmpty()) {
            p.put("birthDate", birth);
        }
        ArrayNode addresses = addresses(pid.field(11));
        if (!addresses.isEmpty()) {
            p.set("address", addresses);
        }
        ArrayNode telecom = telecom(pid.field(13));
        if (!telecom.isEmpty()) {
            p.set("telecom", telecom);
        }
        if (pid.field(30).equalsIgnoreCase("Y")) {
            p.put("deceasedBoolean", true);
        }
        String ifNoneExist = firstValue == null ? null : "identifier=" + firstSystem + "|" + firstValue;
        patientRef = add(p, ifNoneExist);
    }

    private void encounter(Segment pv1) {
        ObjectNode e = resource("Encounter");
        String event = message.header().triggerEvent();
        e.put("status", event.equals("A03") ? "finished" : event.equals("A05") || event.equals("A14")
                ? "planned" : event.equals("A11") ? "cancelled" : "in-progress");
        String cls = switch (pv1.field(2).toUpperCase(Locale.ROOT)) {
            case "I" -> "IMP";
            case "O" -> "AMB";
            case "E" -> "EMER";
            case "P" -> "PRENC";
            default -> "";
        };
        ObjectNode coding = NODES.objectNode();
        coding.put("system", "http://terminology.hl7.org/CodeSystem/v3-ActCode");
        coding.put("code", cls.isEmpty() ? "AMB" : cls);
        e.set("class", coding);
        subject(e, "subject");
        String visit = comp(pv1.field(19), 1);
        if (!visit.isEmpty()) {
            e.putArray("identifier").addObject().put("value", visit);
        }
        String location = String.join(" ", nonEmpty(comp(pv1.field(3), 1), comp(pv1.field(3), 2),
                comp(pv1.field(3), 3)));
        if (!location.isEmpty()) {
            e.putArray("location").addObject().putObject("location").put("display", location);
        }
        String attending = practitionerName(pv1.field(7));
        if (!attending.isEmpty()) {
            e.putArray("participant").addObject().putObject("individual").put("display", attending);
        }
        String start = dateTime(pv1.field(44));
        String end = dateTime(pv1.field(45));
        if (!start.isEmpty() || !end.isEmpty()) {
            ObjectNode period = e.putObject("period");
            if (!start.isEmpty()) {
                period.put("start", start);
            }
            if (!end.isEmpty()) {
                period.put("end", end);
            }
        }
        encounterRef = add(e, null);
    }

    private void relatedPerson(Segment nk1) {
        ObjectNode r = resource("RelatedPerson");
        if (patientRef != null) {
            r.putObject("patient").put("reference", patientRef);
        }
        ObjectNode name = humanName(nk1.field(2));
        if (name != null) {
            r.putArray("name").add(name);
        }
        String rel = comp(nk1.field(3), 1);
        if (!rel.isEmpty()) {
            r.putArray("relationship").add(concept("http://terminology.hl7.org/CodeSystem/v2-0063", rel,
                    comp(nk1.field(3), 2)));
        }
        ArrayNode addresses = addresses(nk1.field(4));
        if (!addresses.isEmpty()) {
            r.set("address", addresses);
        }
        ArrayNode telecom = telecom(nk1.field(5));
        if (!telecom.isEmpty()) {
            r.set("telecom", telecom);
        }
        add(r, null);
    }

    private void allergy(Segment al1) {
        ObjectNode a = resource("AllergyIntolerance");
        a.set("code", codeable(al1.field(3)));
        subject(a, "patient");
        String severity = al1.field(4).toUpperCase(Locale.ROOT);
        if (severity.equals("SV")) {
            a.put("criticality", "high");
        } else if (severity.equals("MI") || severity.equals("MO")) {
            a.put("criticality", "low");
        }
        String category = switch (comp(al1.field(2), 1).toUpperCase(Locale.ROOT)) {
            case "DA" -> "medication";
            case "FA" -> "food";
            case "EA" -> "environment";
            default -> "";
        };
        if (!category.isEmpty()) {
            a.putArray("category").add(category);
        }
        String reaction = al1.field(5);
        if (!reaction.isEmpty()) {
            a.putArray("reaction").addObject().putArray("manifestation").addObject().put("text", text(reaction));
        }
        add(a, null);
    }

    private void condition(Segment dg1) {
        ObjectNode c = resource("Condition");
        ObjectNode code = codeable(dg1.field(3));
        if (code.isEmpty() && !dg1.field(4).isEmpty()) {
            code.put("text", text(dg1.field(4)));
        }
        c.set("code", code);
        subject(c, "subject");
        if (encounterRef != null) {
            c.putObject("encounter").put("reference", encounterRef);
        }
        String onset = dateTime(dg1.field(5));
        if (!onset.isEmpty()) {
            c.put("onsetDateTime", onset);
        }
        add(c, null);
    }

    /** OBR (with its ORC and the OBX segments after it) to ServiceRequest, or DiagnosticReport and Observations. */
    private void orders(String messageCode) {
        boolean results = messageCode.equals("ORU") || messageCode.equals("OUL");
        boolean orders = messageCode.equals("ORM") || messageCode.equals("OML");
        List<Segment> segments = message.segments();
        Segment orc = null;
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (s.name().equals("ORC")) {
                orc = s;
            } else if (s.name().equals("OBR")) {
                List<Segment> obx = new ArrayList<>();
                for (int j = i + 1; j < segments.size() && !segments.get(j).name().equals("OBR")
                        && !segments.get(j).name().equals("ORC"); j++) {
                    if (segments.get(j).name().equals("OBX")) {
                        obx.add(segments.get(j));
                    }
                }
                if (results) {
                    report(s, obx);
                } else if (orders) {
                    serviceRequest(orc, s);
                } else {
                    notes.add("OBR in a " + messageCode + " message was not converted");
                }
                orc = null;
            } else if (s.name().equals("OBX") && !results && !orders) {
                observation(s, null);
            }
        }
    }

    private void serviceRequest(Segment orc, Segment obr) {
        ObjectNode r = resource("ServiceRequest");
        String control = orc == null ? "NW" : orc.field(1).toUpperCase(Locale.ROOT);
        r.put("status", switch (control) {
            case "CA", "OC" -> "revoked";
            case "HD", "OH" -> "on-hold";
            case "DC", "OD" -> "completed";
            default -> "active";
        });
        r.put("intent", "order");
        ArrayNode ids = NODES.arrayNode();
        String placer = comp(orc != null && !orc.field(2).isEmpty() ? orc.field(2) : obr.field(2), 1);
        String filler = comp(orc != null && !orc.field(3).isEmpty() ? orc.field(3) : obr.field(3), 1);
        if (!placer.isEmpty()) {
            ids.addObject().put("value", placer).set("type", concept(
                    "http://terminology.hl7.org/CodeSystem/v2-0203", "PLAC", "Placer Identifier"));
        }
        if (!filler.isEmpty()) {
            ids.addObject().put("value", filler).set("type", concept(
                    "http://terminology.hl7.org/CodeSystem/v2-0203", "FILL", "Filler Identifier"));
        }
        if (!ids.isEmpty()) {
            r.set("identifier", ids);
        }
        r.set("code", codeable(obr.field(4)));
        subject(r, "subject");
        if (encounterRef != null) {
            r.putObject("encounter").put("reference", encounterRef);
        }
        String requested = dateTime(obr.field(7));
        if (!requested.isEmpty()) {
            r.put("occurrenceDateTime", requested);
        }
        String requester = practitionerName(orc != null && !orc.field(12).isEmpty() ? orc.field(12) : obr.field(16));
        if (!requester.isEmpty()) {
            r.putObject("requester").put("display", requester);
        }
        add(r, null);
    }

    private void report(Segment obr, List<Segment> obx) {
        ArrayNode results = NODES.arrayNode();
        for (Segment o : obx) {
            results.addObject().put("reference", observation(o, obr));
        }
        ObjectNode r = resource("DiagnosticReport");
        r.put("status", resultStatus(obr.field(25)));
        r.set("code", codeable(obr.field(4)));
        subject(r, "subject");
        String effective = dateTime(obr.field(7));
        if (!effective.isEmpty()) {
            r.put("effectiveDateTime", effective);
        }
        String issued = dateTime(obr.field(22));
        if (!issued.isEmpty()) {
            r.put("issued", instant(issued));
        }
        String filler = comp(obr.field(3), 1);
        if (!filler.isEmpty()) {
            r.putArray("identifier").addObject().put("value", filler);
        }
        if (!results.isEmpty()) {
            r.set("result", results);
        }
        add(r, null);
    }

    private String observation(Segment obx, Segment obr) {
        ObjectNode o = resource("Observation");
        o.put("status", resultStatus(obx.field(11)));
        o.set("code", codeable(obx.field(3)));
        subject(o, "subject");
        String type = obx.field(2).toUpperCase(Locale.ROOT);
        String value = obx.field(5);
        switch (type) {
            case "NM", "SN" -> {
                ObjectNode q = NODES.objectNode();
                try {
                    q.put("value", new java.math.BigDecimal(type.equals("SN") ? comp(value, 2) : value.trim()));
                } catch (NumberFormatException e) {
                    q = null;
                    o.put("valueString", text(value));
                }
                if (q != null) {
                    String unit = comp(obx.field(6), 1);
                    if (!unit.isEmpty()) {
                        q.put("unit", text(unit));
                        q.put("system", CODE_SYSTEMS.get("UCUM"));
                        q.put("code", unit);
                    }
                    o.set("valueQuantity", q);
                }
            }
            case "CE", "CWE", "CNE" -> o.set("valueCodeableConcept", codeable(value));
            case "DT", "TS", "DTM" -> o.put("valueDateTime", dateTime(value));
            default -> {
                if (!value.isEmpty()) {
                    o.put("valueString", text(String.join("\n", reps(value))));
                }
            }
        }
        String range = obx.field(7);
        if (!range.isEmpty()) {
            o.putArray("referenceRange").addObject().put("text", text(range));
        }
        String flag = comp(obx.field(8), 1).toUpperCase(Locale.ROOT);
        if (!flag.isEmpty()) {
            o.putArray("interpretation").add(concept(
                    "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation", flag, ""));
        }
        String effective = dateTime(obx.field(14));
        if (effective.isEmpty() && obr != null) {
            effective = dateTime(obr.field(7));
        }
        if (!effective.isEmpty()) {
            o.put("effectiveDateTime", effective);
        }
        return add(o, null);
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    private ObjectNode resource(String type) {
        ObjectNode r = NODES.objectNode();
        r.put("resourceType", type);
        return r;
    }

    /** Adds {@code r} to the bundle and returns its {@code urn:uuid:} reference. */
    private String add(ObjectNode r, String ifNoneExist) {
        String type = r.path("resourceType").asText();
        int n = counts.merge(type, 1, Integer::sum);
        String url = "urn:uuid:" + UUID.nameUUIDFromBytes((seed + "/" + type + "/" + n)
                .getBytes(StandardCharsets.UTF_8));
        ObjectNode entry = entries.addObject();
        entry.put("fullUrl", url);
        entry.set("resource", r);
        ObjectNode request = entry.putObject("request");
        request.put("method", "POST");
        request.put("url", type);
        if (ifNoneExist != null) {
            request.put("ifNoneExist", ifNoneExist);
        }
        return url;
    }

    private void subject(ObjectNode r, String field) {
        if (patientRef != null) {
            r.putObject(field).put("reference", patientRef);
        }
    }

    private List<String> reps(String field) {
        List<String> out = new ArrayList<>();
        for (String r : Segment.split(field, d.repetition())) {
            if (!r.isEmpty()) {
                out.add(r);
            }
        }
        return out;
    }

    private String comp(String value, int n) {
        List<String> c = Segment.split(value, d.component());
        return n <= c.size() ? c.get(n - 1) : "";
    }

    private static List<String> nonEmpty(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private ObjectNode humanName(String xpn) {
        String family = text(comp(xpn, 1));
        List<String> given = nonEmpty(text(comp(xpn, 2)), text(comp(xpn, 3)));
        if (family.isEmpty() && given.isEmpty()) {
            return null;
        }
        ObjectNode n = NODES.objectNode();
        String use = switch (comp(xpn, 7).toUpperCase(Locale.ROOT)) {
            case "L" -> "official";
            case "A" -> "nickname";
            case "M" -> "maiden";
            default -> "";
        };
        if (!use.isEmpty()) {
            n.put("use", use);
        }
        if (!family.isEmpty()) {
            n.put("family", family);
        }
        if (!given.isEmpty()) {
            ArrayNode g = n.putArray("given");
            given.forEach(g::add);
        }
        String prefix = text(comp(xpn, 5));
        if (!prefix.isEmpty()) {
            n.putArray("prefix").add(prefix);
        }
        return n;
    }

    private ArrayNode addresses(String field) {
        ArrayNode out = NODES.arrayNode();
        for (String xad : reps(field)) {
            ObjectNode a = NODES.objectNode();
            List<String> lines = nonEmpty(text(comp(xad, 1)), text(comp(xad, 2)));
            if (!lines.isEmpty()) {
                ArrayNode l = a.putArray("line");
                lines.forEach(l::add);
            }
            putIfPresent(a, "city", text(comp(xad, 3)));
            putIfPresent(a, "state", text(comp(xad, 4)));
            putIfPresent(a, "postalCode", text(comp(xad, 5)));
            putIfPresent(a, "country", text(comp(xad, 6)));
            String use = switch (comp(xad, 7).toUpperCase(Locale.ROOT)) {
                case "H" -> "home";
                case "B", "O" -> "work";
                case "C" -> "temp";
                default -> "";
            };
            putIfPresent(a, "use", use);
            if (!a.isEmpty()) {
                out.add(a);
            }
        }
        return out;
    }

    private ArrayNode telecom(String field) {
        ArrayNode out = NODES.arrayNode();
        for (String xtn : reps(field)) {
            String value = comp(xtn, 1);
            if (value.isEmpty()) {
                value = String.join("", nonEmpty(comp(xtn, 5).isEmpty() ? "" : "+" + comp(xtn, 5), comp(xtn, 6),
                        comp(xtn, 7)));
            }
            String email = comp(xtn, 4);
            ObjectNode t = NODES.objectNode();
            boolean isEmail = comp(xtn, 3).equalsIgnoreCase("Internet") || comp(xtn, 2).equalsIgnoreCase("NET");
            if (isEmail && !email.isEmpty()) {
                t.put("system", "email");
                t.put("value", email);
            } else if (!value.isEmpty()) {
                t.put("system", comp(xtn, 3).equalsIgnoreCase("FX") ? "fax" : "phone");
                t.put("value", text(value));
            } else {
                continue;
            }
            String use = switch (comp(xtn, 2).toUpperCase(Locale.ROOT)) {
                case "PRN" -> "home";
                case "WPN" -> "work";
                case "ORN" -> "mobile";
                default -> "";
            };
            putIfPresent(t, "use", comp(xtn, 3).equalsIgnoreCase("CP") ? "mobile" : use);
            out.add(t);
        }
        return out;
    }

    /** A CE/CWE field as a CodeableConcept: code, display and system, plus text. */
    private ObjectNode codeable(String ce) {
        ObjectNode c = NODES.objectNode();
        String code = comp(ce, 1);
        String display = text(comp(ce, 2));
        if (!code.isEmpty()) {
            ObjectNode coding = c.putArray("coding").addObject();
            String system = comp(ce, 3);
            putIfPresent(coding, "system", CODE_SYSTEMS.getOrDefault(system.toUpperCase(Locale.ROOT),
                    system.isEmpty() ? "" : "urn:hl7v2:" + system));
            coding.put("code", code);
            putIfPresent(coding, "display", display);
        }
        putIfPresent(c, "text", display.isEmpty() ? text(code) : display);
        return c;
    }

    private static ObjectNode concept(String system, String code, String display) {
        ObjectNode c = NODES.objectNode();
        ObjectNode coding = c.putArray("coding").addObject();
        coding.put("system", system);
        coding.put("code", code);
        if (!display.isEmpty()) {
            coding.put("display", display);
        }
        return c;
    }

    private String practitionerName(String xcn) {
        String family = text(comp(xcn, 2));
        String given = text(comp(xcn, 3));
        String prefix = text(comp(xcn, 6));
        return String.join(" ", nonEmpty(prefix, given, family));
    }

    private static String identifierSystem(String authority) {
        String a = authority.contains("&") ? authority.substring(0, authority.indexOf('&')) : authority;
        return a.isEmpty() ? "urn:hl7v2:unknown-authority" : "urn:hl7v2:" + a.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String resultStatus(String v2) {
        return switch (v2.trim().toUpperCase(Locale.ROOT)) {
            case "P", "I", "R", "S" -> "preliminary";
            case "C" -> "corrected";
            case "X", "D", "W" -> "cancelled";
            default -> "final";
        };
    }

    private static void putIfPresent(ObjectNode o, String field, String value) {
        if (value != null && !value.isEmpty()) {
            o.put(field, value);
        }
    }

    /** Decodes HL7 escape sequences (\F\ \S\ \T\ \R\ \E\ and \.br\) in a text value. */
    private String text(String v) {
        if (v.indexOf(d.escape()) < 0) {
            return v;
        }
        String e = String.valueOf(d.escape());
        return v.replace(e + "F" + e, String.valueOf(d.field()))
                .replace(e + "S" + e, String.valueOf(d.component()))
                .replace(e + "T" + e, String.valueOf(d.subcomponent()))
                .replace(e + "R" + e, String.valueOf(d.repetition()))
                .replace(e + ".br" + e, "\n")
                .replace(e + "E" + e, e);
    }

    /** yyyyMMdd... to FHIR date (yyyy, yyyy-MM or yyyy-MM-dd), or empty. */
    static String date(String ts) {
        String t = ts == null ? "" : ts.trim();
        if (!t.matches("\\d{4}(\\d{2}(\\d{2})?)?.*")) {
            return "";
        }
        if (t.length() >= 8) {
            return t.substring(0, 4) + "-" + t.substring(4, 6) + "-" + t.substring(6, 8);
        }
        return t.length() >= 6 ? t.substring(0, 4) + "-" + t.substring(4, 6) : t.substring(0, 4);
    }

    /** yyyyMMddHHmm[ss[.S+]][+/-zzzz] to FHIR dateTime, or empty. A time without a zone is left without one. */
    static String dateTime(String ts) {
        String t = ts == null ? "" : ts.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\.\\d{1,4})?([+-]\\d{4})?")
                .matcher(t);
        if (!m.matches()) {
            return "";
        }
        if (m.group(4) == null || m.group(5) == null) {
            return date(t);
        }
        StringBuilder sb = new StringBuilder(date(t)).append('T').append(m.group(4)).append(':').append(m.group(5))
                .append(':').append(m.group(6) == null ? "00" : m.group(6));
        if (m.group(7) != null) {
            sb.append(m.group(7));
        }
        if (m.group(8) != null) {
            sb.append(m.group(8), 0, 3).append(':').append(m.group(8), 3, 5);
        }
        return sb.toString();
    }

    /** An instant needs a zone; a dateTime without one is taken as UTC. */
    private static String instant(String dateTime) {
        return dateTime.length() > 10 && !dateTime.matches(".*[+-]\\d{2}:\\d{2}$") ? dateTime + "Z" : dateTime;
    }
}
