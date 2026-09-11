package reconcile

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"hash"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// How drift is noticed.
//
// The alternative was to read a container's whole configuration back out of the engine and
// compare it, field by field, with what the spec asks for. That comparison is where this
// kind of loop goes wrong: the engine normalises values on the way in - a memory limit
// rounded to a page, an empty entrypoint replaced by the image's own, a label the daemon
// adds itself - so a naive comparison finds a difference on every pass and recreates a
// perfectly good container every fifteen seconds, forever.
//
// So the desired configuration is hashed instead, and the hash is stamped on the container
// in LabelFingerprint when it is created. Two hashes that differ mean the spec changed;
// two that match mean it did not. Nothing is read back and normalised, and the comparison
// costs one string equality.

// fingerprintVersion prefixes every hash.
//
// Changing what goes into the hash changes every container's fingerprint, and the next
// pass on every node in the fleet then recreates every container - a whole-platform
// restart, from one edit here. The prefix makes that visible in a log line and in
// `docker inspect` instead of being a mystery, and bumping it is a deliberate act with a
// note in the commit message, not a side effect.
const fingerprintVersion = "wf1"

// Fingerprint is the hash of everything about a workload that shapes its container.
//
// Deliberately not in it:
//
//   - Desired. Starting and stopping a container must not destroy it: a customer who
//     pressed pause expects their logs and their volumes to still be there afterwards.
//   - ReleaseID and Site. A site has no container, and an app that mounts a site's release
//     sees the symlink move underneath a mount whose target never changes.
//
// Everything else is in, including the fields this node cannot enforce on this filesystem.
// A disk quota that is advisory here is still part of what the panel asked for, and a
// container created before the node was moved to XFS should be rebuilt once it can be
// honoured.
func Fingerprint(workload spec.Workload) string {
	c := canonical{digest: sha256.New()}
	c.text(fingerprintVersion)
	c.text(workload.ID)
	c.text(string(workload.Kind))
	c.text(workload.Name)
	c.text(workload.Image)
	c.text(workload.ImageDigest)
	c.list(workload.Entrypoint)
	c.list(workload.Command)
	c.text(workload.WorkingDir)

	c.number(int64(len(workload.Env)))
	for _, entry := range workload.Env {
		c.text(entry.Name)
		c.text(entry.Value)
		c.flag(entry.Secret)
	}

	c.number(workload.Limits.NanoCPUs)
	c.number(workload.Limits.MemoryBytes)
	c.number(workload.Limits.MemorySwapBytes)
	c.number(workload.Limits.PidsLimit)
	c.number(workload.Limits.DiskBytes)
	c.number(workload.Limits.NofileLimit)

	c.number(int64(len(workload.Mounts)))
	for _, mount := range workload.Mounts {
		c.text(mount.VolumeID)
		c.text(string(mount.Kind))
		c.text(mount.Target)
		c.flag(mount.ReadOnly)
		c.number(mount.QuotaBytes)
		c.number(mount.TmpfsBytes)
	}

	c.number(int64(len(workload.Ports)))
	for _, port := range workload.Ports {
		c.number(int64(port.Container))
		c.number(int64(port.Host))
		c.text(string(port.Protocol))
		c.text(port.HostIP)
	}

	c.text(string(workload.Restart.Mode))
	c.number(int64(workload.Restart.MaxRetries))
	c.text(string(workload.Runtime))
	c.text(workload.TenantNetwork)
	c.flag(workload.ReadOnlyRootfs)
	c.text(workload.User)
	c.number(int64(workload.StopGrace))

	c.list(workload.Health.Test)
	c.number(int64(workload.Health.Interval))
	c.number(int64(workload.Health.Timeout))
	c.number(int64(workload.Health.Retries))
	c.number(int64(workload.Health.StartPeriod))

	return fingerprintVersion + ":" + hex.EncodeToString(c.digest.Sum(nil))
}

// canonical writes values into a hash unambiguously.
//
// Every string is preceded by its length, which is the whole point: without it the two
// workloads {Name: "ab", Image: "c"} and {Name: "a", Image: "bc"} hash identically, and a
// rename that happened to shift one character between two fields would be invisible drift.
type canonical struct {
	digest hash.Hash
}

func (c *canonical) text(value string) {
	c.number(int64(len(value)))
	c.digest.Write([]byte(value))
}

func (c *canonical) number(value int64) {
	var encoded [8]byte
	binary.BigEndian.PutUint64(encoded[:], uint64(value))
	c.digest.Write(encoded[:])
}

func (c *canonical) flag(value bool) {
	if value {
		c.digest.Write([]byte{1})
		return
	}
	c.digest.Write([]byte{0})
}

func (c *canonical) list(values []string) {
	c.number(int64(len(values)))
	for _, value := range values {
		c.text(value)
	}
}

// short is the first eight hex characters, for a log line. The full hash is on the
// container; a line saying which of two hashes changed only needs to be recognisable.
func short(fingerprint string) string {
	const prefix = len(fingerprintVersion) + 1
	if len(fingerprint) <= prefix+8 {
		return fingerprint
	}
	return fingerprint[:prefix+8]
}
