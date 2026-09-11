package lhqm.furimeo.wisper.node;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns the JSON in {@code node_status.doctor_report} back into something the panel can
 * read.
 *
 * <p>Two callers want it and want different halves: the node's page renders every check,
 * and {@link RequestNodeUpgrade} needs the architecture so it can pick the right binary.
 * Both go through here so the column is parsed in one place and a document written by an
 * older panel cannot break two screens in two different ways.
 *
 * <p>An unparseable document returns empty rather than throwing. The report is diagnostic;
 * a node's page that will not render because a field was renamed six months ago is a worse
 * outcome than a page that says the report could not be read.
 */
@Component
public class ReadStoredDoctorReport {

    private static final Logger log = LoggerFactory.getLogger(ReadStoredDoctorReport.class);

    private final ObjectMapper json;

    public ReadStoredDoctorReport(ObjectMapper json) {
        this.json = json;
    }

    /** Decodes the stored document, or empty when there is none or it will not parse. */
    public Optional<NodeDoctorReport> of(String document) {
        if (document == null || document.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(document, NodeDoctorReport.class));
        } catch (JacksonException unreadable) {
            log.warn("A stored doctor report could not be parsed: {}", unreadable.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The GOARCH the machine reported, which is what decides the binary an upgrade sends.
     *
     * <p>It lives inside the report rather than in a column of its own because
     * {@code node_status} has no architecture column and a machine's architecture does not
     * change without the machine being replaced - at which point it re-enrols and the
     * report is rewritten.
     */
    public Optional<String> architectureIn(String document) {
        return of(document)
                .map(NodeDoctorReport::machine)
                .map(NodeDoctorReport.Machine::architecture)
                .filter(architecture -> !architecture.isBlank());
    }
}
