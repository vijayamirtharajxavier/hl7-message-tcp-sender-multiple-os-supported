package io.hl7sender.core.fhir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.hl7sender.core.samples.SampleMessages;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class V2ToFhirTest {

    private static String sample(String prefix) {
        return SampleMessages.all().stream().filter(s -> s.text().contains(prefix)).findFirst().orElseThrow().text();
    }

    private static List<JsonNode> resources(V2ToFhir.Result r, String type) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode e : r.bundle().path("entry")) {
            if (e.path("resource").path("resourceType").asText().equals(type)) {
                out.add(e.path("resource"));
            }
        }
        return out;
    }

    @Test
    void admitBecomesPatientEncounterRelatedPersonAndAllergy() {
        V2ToFhir.Result r = V2ToFhir.convert(sample("ADT^A01"));
        JsonNode bundle = r.bundle();
        assertThat(bundle.path("resourceType").asText()).isEqualTo("Bundle");
        assertThat(bundle.path("type").asText()).isEqualTo("transaction");
        assertThat(bundle.path("timestamp").asText()).isEqualTo("2026-01-01T12:00:00");
        assertThat(r.resources()).containsEntry("Patient", 1).containsEntry("Encounter", 1)
                .containsEntry("RelatedPerson", 1).containsEntry("AllergyIntolerance", 1);

        JsonNode patient = resources(r, "Patient").get(0);
        assertThat(patient.path("identifier").get(0).path("value").asText()).isEqualTo("MRN100001");
        assertThat(patient.path("identifier").get(0).path("system").asText()).isEqualTo("urn:hl7v2:DEMO_HOSP");
        assertThat(patient.path("identifier").get(0).path("type").path("coding").get(0).path("code").asText())
                .isEqualTo("MR");
        assertThat(patient.path("name").get(0).path("family").asText()).isEqualTo("DOE");
        assertThat(patient.path("name").get(0).path("given").toString()).isEqualTo("[\"JANE\",\"Q\"]");
        assertThat(patient.path("name").get(0).path("use").asText()).isEqualTo("official");
        assertThat(patient.path("gender").asText()).isEqualTo("female");
        assertThat(patient.path("birthDate").asText()).isEqualTo("1980-01-01");
        assertThat(patient.path("address").get(0).path("city").asText()).isEqualTo("SPRINGFIELD");
        assertThat(patient.path("address").get(0).path("use").asText()).isEqualTo("home");
        assertThat(patient.path("telecom").get(0).path("value").asText()).isEqualTo("+12175550101");
        // Conditional create: the patient is not duplicated if it already exists.
        JsonNode patientEntry = bundle.path("entry").get(0);
        assertThat(patientEntry.path("request").path("ifNoneExist").asText())
                .isEqualTo("identifier=urn:hl7v2:DEMO_HOSP|MRN100001");
        String patientRef = patientEntry.path("fullUrl").asText();
        assertThat(patientRef).startsWith("urn:uuid:");

        JsonNode encounter = resources(r, "Encounter").get(0);
        assertThat(encounter.path("status").asText()).isEqualTo("in-progress");
        assertThat(encounter.path("class").path("code").asText()).isEqualTo("IMP");
        assertThat(encounter.path("subject").path("reference").asText()).isEqualTo(patientRef);
        assertThat(encounter.path("participant").get(0).path("individual").path("display").asText())
                .isEqualTo("DR ADAM SMITH");
        assertThat(encounter.path("period").path("start").asText()).isEqualTo("2026-01-01T11:55:00");

        JsonNode allergy = resources(r, "AllergyIntolerance").get(0);
        assertThat(allergy.path("code").path("coding").get(0).path("system").asText())
                .isEqualTo("http://www.nlm.nih.gov/research/umls/rxnorm");
        assertThat(allergy.path("criticality").asText()).isEqualTo("high");
        assertThat(allergy.path("category").get(0).asText()).isEqualTo("medication");
        assertThat(allergy.path("patient").path("reference").asText()).isEqualTo(patientRef);

        assertThat(resources(r, "RelatedPerson").get(0).path("relationship").get(0).path("coding").get(0)
                .path("code").asText()).isEqualTo("SPO");
        assertThat(r.notes()).isEmpty();
        // The same message always gives the same bundle.
        assertThat(V2ToFhir.convert(sample("ADT^A01")).json()).isEqualTo(r.json());
    }

    @Test
    void resultsBecomeADiagnosticReportWithObservations() {
        V2ToFhir.Result r = V2ToFhir.convert(sample("ORU^R01"));
        assertThat(r.resources()).containsEntry("DiagnosticReport", 1).containsEntry("Observation", 3);
        List<JsonNode> obs = resources(r, "Observation");
        JsonNode hb = obs.get(0);
        assertThat(hb.path("code").path("coding").get(0).path("system").asText()).isEqualTo("http://loinc.org");
        assertThat(hb.path("code").path("coding").get(0).path("code").asText()).isEqualTo("718-7");
        assertThat(hb.path("valueQuantity").path("value").decimalValue()).isEqualByComparingTo("13.5");
        assertThat(hb.path("valueQuantity").path("unit").asText()).isEqualTo("g/dL");
        assertThat(hb.path("referenceRange").get(0).path("text").asText()).isEqualTo("12.0-16.0");
        assertThat(hb.path("interpretation").get(0).path("coding").get(0).path("code").asText()).isEqualTo("N");
        assertThat(hb.path("status").asText()).isEqualTo("final");
        JsonNode report = resources(r, "DiagnosticReport").get(0);
        assertThat(report.path("result")).hasSize(3);
        assertThat(report.path("status").asText()).isEqualTo("final");
        assertThat(report.path("issued").asText()).isEqualTo("2026-01-01T15:55:00Z");
    }

    @Test
    void ordersBecomeServiceRequests() {
        V2ToFhir.Result r = V2ToFhir.convert(sample("ORM^O01"));
        JsonNode sr = resources(r, "ServiceRequest").get(0);
        assertThat(sr.path("status").asText()).isEqualTo("active");
        assertThat(sr.path("intent").asText()).isEqualTo("order");
        assertThat(sr.path("identifier").get(0).path("value").asText()).isEqualTo("ORD100001");
        assertThat(sr.path("code").path("text").asText()).isEqualTo("CBC with differential");
        assertThat(sr.path("requester").path("display").asText()).isEqualTo("DR ADAM SMITH");
        assertThat(sr.path("encounter").path("reference").asText()).startsWith("urn:uuid:");
    }

    @Test
    void textValuesAndUnmappedSegments() {
        String m = "MSH|^~\\&|A|B|C|D|20260101120000+0100||ORU^R01|X1|P|2.5.1\r"
                + "PID|1||42^^^H^MR||O\\S\\BRIEN^PAT\r"
                + "OBR|1|||NOTE^Note\r"
                + "OBX|1|TX|NOTE^Note||line one~line two|||A\r"
                + "OBX|2|NM|X^Y||not a number\r"
                + "ZPI|1|custom\r";
        V2ToFhir.Result r = V2ToFhir.convert(m);
        assertThat(r.bundle().path("timestamp").asText()).isEqualTo("2026-01-01T12:00:00+01:00");
        assertThat(resources(r, "Patient").get(0).path("name").get(0).path("family").asText()).isEqualTo("O^BRIEN");
        List<JsonNode> obs = resources(r, "Observation");
        assertThat(obs.get(0).path("valueString").asText()).isEqualTo("line one\nline two");
        assertThat(obs.get(1).path("valueString").asText()).isEqualTo("not a number");
        assertThat(r.notes()).containsExactly("Segment ZPI was not converted");
        assertThatThrownBy(() -> V2ToFhir.convert("not hl7")).hasMessageContaining("MSH");
    }

    @Test
    void patientClassWithNoEquivalentBecomesNullFlavorUnknown() {
        String base = "MSH|^~\\&|A|B|C|D|20260101120000||ADT^A01|X1|P|2.5.1\r"
                + "PID|1||42^^^H^MR||DOE^JANE\r"
                + "PV1|1|%s|WARD^101^A||||||||||||||||V100\r";
        String nullFlavor = "http://terminology.hl7.org/CodeSystem/v3-NullFlavor";

        V2ToFhir.Result unknown = V2ToFhir.convert(base.formatted("U"));
        JsonNode cls = resources(unknown, "Encounter").get(0).path("class");
        assertThat(cls.path("system").asText()).isEqualTo(nullFlavor);
        assertThat(cls.path("code").asText()).isEqualTo("UNK");
        assertThat(unknown.notes()).containsExactly(
                "PV1-2 (patient class) U has no Encounter.class equivalent: Encounter.class is UNK");

        V2ToFhir.Result empty = V2ToFhir.convert(base.formatted(""));
        assertThat(resources(empty, "Encounter").get(0).path("class").path("code").asText()).isEqualTo("UNK");
        assertThat(empty.notes()).containsExactly("PV1-2 (patient class) is empty: Encounter.class is UNK");

        V2ToFhir.Result outpatient = V2ToFhir.convert(base.formatted("O"));
        JsonNode amb = resources(outpatient, "Encounter").get(0).path("class");
        assertThat(amb.path("system").asText()).isEqualTo("http://terminology.hl7.org/CodeSystem/v3-ActCode");
        assertThat(amb.path("code").asText()).isEqualTo("AMB");
        assertThat(outpatient.notes()).isEmpty();
    }

    @Test
    void dates() {
        assertThat(V2ToFhir.date("19800101")).isEqualTo("1980-01-01");
        assertThat(V2ToFhir.date("198001")).isEqualTo("1980-01");
        assertThat(V2ToFhir.date("")).isEmpty();
        assertThat(V2ToFhir.dateTime("202601011530")).isEqualTo("2026-01-01T15:30:00");
        assertThat(V2ToFhir.dateTime("20260101153045.123-0500")).isEqualTo("2026-01-01T15:30:45.123-05:00");
        assertThat(V2ToFhir.dateTime("20260101")).isEqualTo("2026-01-01");
        assertThat(V2ToFhir.dateTime("junk")).isEmpty();
    }
}
