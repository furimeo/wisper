package bootstrap

import (
	"fmt"
	"io"
	"strings"

	"google.golang.org/protobuf/encoding/protojson"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// writeReportJSON prints the report as the panel will read it.
//
// protojson rather than encoding/json, so the field names, the enum spellings and the
// timestamp format are the ones in node.proto. The installer parses this, and a hand-
// written encoder here would be a second definition of the wire format that drifts from
// the first one the day somebody renames a field.
func writeReportJSON(out io.Writer, report *wisperpb.DoctorReport) error {
	encoded, err := protojson.MarshalOptions{
		Multiline:       true,
		Indent:          "  ",
		EmitUnpopulated: true,
	}.Marshal(report)
	if err != nil {
		return fmt.Errorf("encode the doctor report: %w", err)
	}
	if _, err := out.Write(append(encoded, '\n')); err != nil {
		return err
	}
	return nil
}

// writeReportText prints the report for a person standing at a terminal.
//
// Every failing and warning check prints its remedy underneath it. A check that says
// "no" without saying "then do this" is a support ticket rather than a check, which is
// why DoctorCheck carries the field at all.
func writeReportText(out io.Writer, report *wisperpb.DoctorReport) {
	fmt.Fprintf(out, "sasayaki doctor - agent %s - %s\n\n",
		report.GetAgentVersion(), report.GetTakenAt().AsTime().Format("2006-01-02 15:04:05 MST"))

	width := 0
	for _, item := range report.GetChecks() {
		if len(item.GetId()) > width {
			width = len(item.GetId())
		}
	}

	for _, item := range report.GetChecks() {
		fmt.Fprintf(out, "  %-4s  %-*s  %s\n", outcomeLabel(item.GetOutcome()), width,
			item.GetId(), item.GetDetail())
		if item.GetOutcome() != outcomePass && item.GetRemedy() != "" {
			for _, line := range wrap(item.GetRemedy(), 68) {
				fmt.Fprintf(out, "        %*s  %s\n", width, "", line)
			}
		}
	}

	writeMachineFacts(out, report.GetMachine())
	writeSummary(out, report)
}

func writeMachineFacts(out io.Writer, facts *wisperpb.MachineFacts) {
	if facts == nil {
		return
	}
	fmt.Fprintln(out, "\nmachine")
	rows := [][2]string{
		{"hostname", facts.GetHostname()},
		{"os", facts.GetOperatingSystem()},
		{"kernel", facts.GetKernelVersion()},
		{"architecture", facts.GetArchitecture()},
		{"cpu cores", fmt.Sprint(facts.GetCpuCores())},
		{"memory", formatBytes(facts.GetMemoryBytes())},
		{"disk", fmt.Sprintf("%s free of %s", formatBytes(facts.GetDiskFreeBytes()),
			formatBytes(facts.GetDiskTotalBytes()))},
		{"state filesystem", fmt.Sprintf("%s (project quota: %s)",
			orUnknown(facts.GetStateFilesystem()), yesNo(facts.GetProjectQuotaSupported()))},
		{"docker", strings.TrimSpace(facts.GetDockerVersion() + " api " + facts.GetDockerApiVersion())},
		{"runsc", runscSummary(facts)},
		{"advertise", strings.Join(facts.GetAdvertiseAddresses(), ", ")},
	}
	for _, row := range rows {
		value := row[1]
		if strings.TrimSpace(value) == "" {
			value = "unknown"
		}
		fmt.Fprintf(out, "  %-18s %s\n", row[0], value)
	}
}

func writeSummary(out io.Writer, report *wisperpb.DoctorReport) {
	failures, warnings := countOutcomes(report)
	fmt.Fprintf(out, "\n%d failure%s, %d warning%s. ",
		failures, plural(failures, "", "s"), warnings, plural(warnings, "", "s"))

	if report.GetRequiredChecksPassed() {
		fmt.Fprintln(out, "This machine can host workloads.")
		return
	}
	fmt.Fprintln(out, "This machine cannot host workloads until the failures above are fixed.")
	fmt.Fprintln(out, "Nothing has been installed or changed.")
}

func outcomeLabel(outcome wisperpb.DoctorOutcome) string {
	switch outcome {
	case outcomePass:
		return "PASS"
	case outcomeWarn:
		return "WARN"
	case outcomeFail:
		return "FAIL"
	default:
		return "????"
	}
}

func runscSummary(facts *wisperpb.MachineFacts) string {
	if !facts.GetRunscAvailable() {
		return "not available - workloads run under runc"
	}
	if facts.GetRunscVersion() == "" {
		return "available"
	}
	return "available (" + facts.GetRunscVersion() + ")"
}

func yesNo(value bool) string {
	if value {
		return "yes"
	}
	return "no"
}

// formatBytes prints a size the way an administrator reads one. Binary units, because
// that is what the kernel reports and what `df -h` shows, and one decimal place, because
// "10.7 GiB" is a number and "11529215590 bytes" is a string to be counted on a screen.
func formatBytes(bytes int64) string {
	if bytes < 0 {
		return "unknown"
	}
	if bytes < 1024 {
		return fmt.Sprintf("%d B", bytes)
	}
	units := []string{"KiB", "MiB", "GiB", "TiB", "PiB"}
	// The first division is what gets it into KiB, and it happens before the loop rather
	// than inside it: starting at zero with the value still in bytes labels every size one
	// unit too large, and a report that calls sixteen gibibytes of RAM sixteen tebibytes is
	// worse than one that prints the raw number.
	value := float64(bytes) / 1024
	unit := 0
	for value >= 1024 && unit < len(units)-1 {
		value /= 1024
		unit++
	}
	return fmt.Sprintf("%.1f %s", value, units[unit])
}

// wrap breaks a remedy into lines that fit under an indented column, without splitting a
// word. Long enough to read, short enough to stay inside an eighty-column terminal once
// the indent is added.
func wrap(text string, width int) []string {
	words := strings.Fields(text)
	if len(words) == 0 {
		return nil
	}

	lines := make([]string, 0, 4)
	current := words[0]
	for _, word := range words[1:] {
		if len(current)+1+len(word) > width {
			lines = append(lines, current)
			current = word
			continue
		}
		current += " " + word
	}
	return append(lines, current)
}
