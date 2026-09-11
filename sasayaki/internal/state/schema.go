package state

// The schema, in the order it was built.
//
// The statements themselves live next to the code that reads and writes each table -
// specSchema in spec.go, uploadSchema in upload.go - so a table and its queries are one
// edit. What lives here is the part that must be decided globally: which version number a
// table got, and therefore in what order a fresh database is built.
//
// The two rules that make this work:
//
//  1. A shipped migration is frozen. Changing the DDL of a version that has already run
//     on a node changes nothing on that node - it is in the ledger - so the fleet ends up
//     with two different schemas answering to the same version number. Add a new
//     migration instead.
//  2. Append only. A version inserted in the middle applies in the wrong order on a fresh
//     node and never applies at all on an existing one. checkOrder refuses a list that is
//     not strictly increasing, which catches the merge that produced two version 7s.
func schema() []migration {
	return []migration{
		{Version: 1, Name: "spec", Statements: specSchema},
		{Version: 2, Name: "convergence", Statements: convergenceSchema},
		{Version: 3, Name: "workload_status", Statements: workloadStatusSchema},
		{Version: 4, Name: "uploads", Statements: uploadSchema},
		{Version: 5, Name: "certificates", Statements: certificateSchema},
		{Version: 6, Name: "builds", Statements: buildSchema},
		{Version: 7, Name: "backups", Statements: backupSchema},
		{Version: 8, Name: "restores", Statements: restoreSchema},
		{Version: 9, Name: "cron_runs", Statements: cronSchema},
		{Version: 10, Name: "enrolment", Statements: enrolmentSchema},
	}
}

// nodeScopedTables is everything that belongs to one node's enrolment and means nothing
// after the machine has been re-enrolled as a different node against a different panel.
//
// SaveEnrolment empties these when the node id changes. The list is here, beside the
// migrations, because the two are the same decision: a table added above without a line
// added here would survive a re-enrolment and hand the new panel the old one's workloads.
//
// upload_range is absent on purpose - it is deleted by the foreign key on upload_session.
func nodeScopedTables() []string {
	return []string{
		"node_spec",
		"convergence",
		"workload_status",
		"upload_session",
		"certificate",
		"build_run",
		"backup_run",
		"restore_run",
		"cron_run",
	}
}
