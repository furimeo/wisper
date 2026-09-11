package rpc

import (
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// resultFor waits for the CommandResult answering one command id.
func resultFor(t *testing.T, session *panelSession, commandID string) *wisperpb.CommandResult {
	t.Helper()
	return session.expect(t, "a result for "+commandID, func(m *wisperpb.NodeMessage) bool {
		return m.GetCommandResult().GetCommandId() == commandID
	}).GetCommandResult()
}

func TestCommandsAnswerWithTheirTypedOutcome(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()
	startNode(t, panel.credential(), node)
	session := panel.session(t)

	t.Run("backup", func(t *testing.T) {
		session.send(t, &wisperpb.PanelMessage{
			CommandId: "cmd-backup",
			Payload: &wisperpb.PanelMessage_RunBackup{RunBackup: &wisperpb.RunBackup{
				BackupId: "b-1",
				Kind:     wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_VOLUME,
			}},
		})
		receive(t, node.backups, "the backup never started")

		result := resultFor(t, session, "cmd-backup")
		if !result.GetOk() {
			t.Errorf("backup reported failure: %s", result.GetDetail())
		}
		if result.GetBackup().GetRestorePointId() != "rp-1" {
			t.Error("the restore point id must come back with the result, or the panel has no handle to restore from")
		}
		if result.GetFinishedAt() == nil {
			t.Error("a result with no finish time cannot be aged out by the panel")
		}
	})

	t.Run("database", func(t *testing.T) {
		session.send(t, &wisperpb.PanelMessage{
			CommandId: "cmd-db",
			Payload: &wisperpb.PanelMessage_ProvisionDatabase{ProvisionDatabase: &wisperpb.ProvisionDatabase{
				Grant: &wisperpb.DatabaseGrant{
					Id:           "g-1",
					DatabaseName: "customer_1",
					Engine:       wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES,
				},
				Password: "generated-by-the-panel",
			}},
		})
		receive(t, node.provisions, "the database was never provisioned")

		result := resultFor(t, session, "cmd-db")
		if result.GetDatabase().GetDatabaseName() != "customer_1" {
			t.Errorf("provisioned database = %q", result.GetDatabase().GetDatabaseName())
		}
	})

	t.Run("drain", func(t *testing.T) {
		session.send(t, &wisperpb.PanelMessage{
			CommandId: "cmd-drain",
			Payload:   &wisperpb.PanelMessage_Drain{Drain: &wisperpb.DrainNode{EvacuateStateless: true}},
		})
		receive(t, node.drains, "the drain never started")

		result := resultFor(t, session, "cmd-drain")
		if !result.GetDrain().GetComplete() {
			t.Error("the drain report must come back on the result: the panel cannot place workloads elsewhere without it")
		}
	})

	t.Run("upgrade", func(t *testing.T) {
		session.send(t, &wisperpb.PanelMessage{
			CommandId: "cmd-upgrade",
			Payload: &wisperpb.PanelMessage_Upgrade{Upgrade: &wisperpb.UpgradeNode{
				TargetVersion: "v0.2.0",
				DownloadUrl:   "https://panel.test/sasayaki",
				Sha256:        "abc",
			}},
		})
		receive(t, node.upgrades, "the upgrade never started")

		result := resultFor(t, session, "cmd-upgrade")
		if result.GetUpgrade().GetNewVersion() != "v0.2.0" {
			t.Errorf("upgraded to %q", result.GetUpgrade().GetNewVersion())
		}
		if !result.GetOk() {
			t.Error("an upgrade that did not roll back is a success")
		}
	})
}

// A command outliving its stream is the ordinary case, not an edge case: the panel is
// behind a tunnel and a backup takes longer than a tunnel stays up.
func TestCommandSurvivesTheStreamDroppingAndIsAnsweredOnTheNext(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()
	startNode(t, panel.credential(), node)

	first := panel.session(t)
	first.send(t, &wisperpb.PanelMessage{
		CommandId: "cmd-build",
		Payload: &wisperpb.PanelMessage_StartBuild{StartBuild: &wisperpb.StartBuild{
			BuildId:    "d-412",
			WorkloadId: "w-1",
		}},
	})
	receive(t, node.builds, "the build never started")

	// The tunnel drops with the build half-finished.
	first.stop()
	second := panel.session(t)

	// Only now does the build finish. Its result has nowhere to go at the moment it is
	// produced unless the client held on to it.
	close(node.buildGate)

	result := resultFor(t, second, "cmd-build")
	if !result.GetOk() {
		t.Errorf("build reported failure: %s", result.GetDetail())
	}
	if result.GetBuild().GetReleaseId() != "release-1" {
		t.Error("the release id must survive the reconnect: without it the deployment cannot be published")
	}
}

func TestARepeatedCommandRunsOnce(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()
	startNode(t, panel.credential(), node)
	session := panel.session(t)

	command := &wisperpb.PanelMessage{
		CommandId: "cmd-build",
		Payload: &wisperpb.PanelMessage_StartBuild{StartBuild: &wisperpb.StartBuild{
			BuildId: "d-412",
		}},
	}
	session.send(t, command)
	receive(t, node.builds, "the build never started")

	// The panel never saw a result - the stream blinked - so it asks again while the
	// first one is still running. Two builds of the same deployment would race over one
	// release directory.
	session.send(t, command)
	select {
	case <-node.builds:
		t.Fatal("the repeated command started a second build")
	case <-time.After(200 * time.Millisecond):
	}

	close(node.buildGate)
	if result := resultFor(t, session, "cmd-build"); !result.GetOk() {
		t.Errorf("build reported failure: %s", result.GetDetail())
	}
}
