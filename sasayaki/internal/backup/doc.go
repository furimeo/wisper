// Package backup copies a customer's data off this node and puts it back.
//
// Two kinds of subject and nothing in between, because BackupTargetKind has two values and
// the techniques share nothing: a volume is a directory tree that has to stop changing for
// as long as it takes to read it, and a database is a running server that produces a
// consistent logical dump on its own. Inferring which from the id would be a guess made at
// the worst possible moment, so the kind is in the command.
//
// # The shape of a run
//
// Five stages, in the order backup.proto names them, each recorded in SQLite before it
// starts so a machine that goes down leaves behind where it had got to:
//
//	QUIESCE   pause the workload that owns the volume - and nothing else
//	SNAPSHOT  read the tree (or the dump) into a staged archive on local disk, hashing it
//	UPLOAD    push the archive to the destination, in parts, resuming what was interrupted
//	VERIFY    read it back and check the digest, when the command asked for it
//	PRUNE     apply the retention rule at the destination
//
// The pause covers the snapshot and nothing after it. Everything expensive - compressing
// eighty megabytes, pushing them over somebody's ADSL, reading them back to prove they
// arrived - happens against a file on local disk with the customer's application running
// again. BackupCompleted.quiesce_millis is measured across exactly that window rather than
// estimated, because it is the only number in the message a customer actually feels.
//
// # A backup nobody has restored is not a backup
//
// Which is why this package has three separate answers to "is it really there", and none of
// them is a log line saying the upload finished:
//
//   - every archive is hashed while it is written and the digest is stored beside it at the
//     destination, as `<key>.sha256`, in one mechanism for S3 and for a local directory;
//   - `RunBackup.verify` downloads the archive again and re-hashes it, so "verified" never
//     means "not checked";
//   - `RestoreBackup.dry_run` puts a restore point back beside the live data instead of over
//     it - a volume into a sibling directory, a database under a suffixed name - which is the
//     rehearsal that makes the real thing believable.
//
// A restore refuses outright when the stored digest and the bytes disagree, before anything
// is stopped and before anything is overwritten. Restoring a corrupt archive over the only
// good copy of a customer's data is the one failure this package exists to prevent.
//
// # Nothing is overwritten without a way back
//
// A real restore never writes into the live directory. It extracts into a staging tree,
// swings the live path aside with a rename, moves the staging tree in, and keeps what it
// moved aside under backup-rollback/. A restore that dies between the two renames is
// finished on the next start-up by recover.go, which is why the copy is kept rather than
// deleted at the end.
//
// # The file layout
//
//	<state>/volumes/<workload>/<volume>              the live data, as runtime/storage.go has it
//	<state>/backups/<prefix>/<subject>/<object>      DESTINATION_KIND_LOCAL
//	<state>/backup-work/<backup>.tar.gz              the staged archive and its upload journal
//	<state>/backup-rollback/<workload>/<subject>/…   what a restore moved out of the way
//	<state>/backup-restore/<workload>/<subject>/…    where a dry run puts things
//
// # No SDK
//
// The S3 client is four hundred lines of net/http and crypto/hmac rather than a dependency,
// for the reason go.mod gives for every other line in it: sasayaki is one static binary, and
// the AWS SDK is a large transitive tree brought in for six requests - PUT, GET, DELETE,
// list, and the three calls of a multipart upload. Signing is SigV4, endpoints are full URLs
// so MinIO and Backblaze and Wasabi need no special case each, and path-style addressing is
// a field on the destination because it cannot be guessed.
package backup
