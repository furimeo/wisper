package bootstrap

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
)

// The domain separator, and the reason there is one: a bare SHA-256 of a UUID is a value
// that could have been produced for any purpose, and this one has to mean "wisper's
// identifier for a physical machine" and nothing else. Bump the version if what goes into
// the hash ever changes - every enrolled node's fingerprint moves with it.
const fingerprintPrefix = "wisper-fingerprint-v1"

// identitySource is one thing that contributes to the fingerprint, and whether it was
// readable. Both halves are kept, because a fingerprint built from one source is a
// fingerprint that will not spot a clone, and the report has to say so.
type identitySource struct {
	name  string
	value string
	err   error
}

// machineIdentity is what this machine is, and where that came from.
type machineIdentity struct {
	// Fingerprint is the hex SHA-256 the panel stores. Empty when nothing identifying
	// could be read at all.
	Fingerprint string

	// Sources is every candidate, in the order they are hashed, readable or not.
	Sources []identitySource
}

// readMachineIdentity derives a stable identifier for this physical machine.
//
// Two families of source, and both are needed for the reason the whole thing exists.
// /etc/machine-id is present on every systemd machine and is copied when a VM is cloned,
// so on its own it catches the clone. The DMI serials are not copied by every hypervisor,
// so on their own they catch the case where somebody regenerated the machine-id. Together
// they are hard to end up with two of by accident.
//
// What the panel does with a duplicate is suspend both nodes and tell an administrator,
// rather than quietly splitting one customer's workload between two machines that both
// believe they own it (design section 7.3). That decision belongs to the panel; this
// function's whole job is to make sure the input to it is worth something.
//
// The DMI files are mode 0400. Read as a non-root user they simply fail, which is why
// the privileges check runs first and says so.
func readMachineIdentity(m *machine) machineIdentity {
	sources := []identitySource{
		read("machine-id", func() (string, error) { return m.readEtc("machine-id") }),
		// The D-Bus copy is what a machine without /etc/machine-id has; on most systems
		// they are the same file. Included so a non-systemd host still has an identifier.
		read("dbus-machine-id", func() (string, error) {
			return readTrimmed(m.dbusMachineIDPath)
		}),
		read("product-uuid", func() (string, error) {
			return m.readSys("class", "dmi", "id", "product_uuid")
		}),
		read("board-serial", func() (string, error) {
			return m.readSys("class", "dmi", "id", "board_serial")
		}),
		read("product-serial", func() (string, error) {
			return m.readSys("class", "dmi", "id", "product_serial")
		}),
	}

	var material strings.Builder
	material.WriteString(fingerprintPrefix)
	material.WriteString("\n")
	contributing := 0
	for _, source := range sources {
		if source.value == "" {
			continue
		}
		fmt.Fprintf(&material, "%s=%s\n", source.name, source.value)
		contributing++
	}

	identity := machineIdentity{Sources: sources}
	if contributing == 0 {
		return identity
	}
	sum := sha256.Sum256([]byte(material.String()))
	identity.Fingerprint = hex.EncodeToString(sum[:])
	return identity
}

// read runs one source and normalises what it produced. Placeholders that several
// hypervisors and most consumer boards write into the DMI fields are discarded: hashing
// "To Be Filled By O.E.M." would give every machine of that make the same serial and
// turn the clone check into a false alarm generator.
func read(name string, load func() (string, error)) identitySource {
	value, err := load()
	if err != nil {
		return identitySource{name: name, err: err}
	}
	if isPlaceholderSerial(value) {
		return identitySource{
			name: name,
			err:  fmt.Errorf("the firmware wrote a placeholder here: %q", value),
		}
	}
	return identitySource{name: name, value: strings.TrimSpace(value)}
}

// isPlaceholderSerial recognises the strings firmware writes when it has nothing to say.
func isPlaceholderSerial(value string) bool {
	normalised := strings.ToLower(strings.TrimSpace(value))
	if normalised == "" {
		return true
	}
	for _, placeholder := range []string{
		"to be filled by o.e.m.",
		"to be filled by oem",
		"system serial number",
		"default string",
		"none",
		"not specified",
		"not applicable",
		"na",
		"n/a",
		"0",
		"00000000-0000-0000-0000-000000000000",
	} {
		if normalised == placeholder {
			return true
		}
	}
	// A serial made entirely of zeroes, dashes or dots is the same thing said differently.
	return strings.Trim(normalised, "0-. ") == ""
}

// readable is the names of the sources that produced something, for the report.
func (i machineIdentity) readable() []string {
	names := make([]string, 0, len(i.Sources))
	for _, source := range i.Sources {
		if source.value != "" {
			names = append(names, source.name)
		}
	}
	return names
}

// hasHardwareSerial reports whether anything the firmware owns contributed. Without one,
// cloning a virtual machine produces two nodes with the same fingerprint only if the
// machine-id was copied too - which is the common case, but not the only one.
func (i machineIdentity) hasHardwareSerial() bool {
	for _, source := range i.Sources {
		if source.value == "" {
			continue
		}
		switch source.name {
		case "product-uuid", "board-serial", "product-serial":
			return true
		}
	}
	return false
}
