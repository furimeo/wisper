package state

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
	"google.golang.org/protobuf/types/known/timestamppb"
)

// What every test in this package shares: a database on a temporary path, and a clock that
// does not move unless a test moves it. Nothing here reads time.Now - a test that sleeps to
// make an expiry happen is a test that is slow when it passes and flaky when it does not.

// noon is the moment most of these tests happen at. Whole seconds, because timestamps are
// stored to the millisecond and a comparison that fails on rounding teaches nobody
// anything.
var noon = time.Date(2026, time.March, 4, 12, 0, 0, 0, time.UTC)

// openStore is an empty, migrated database on a path that disappears with the test.
func openStore(t *testing.T) *Store {
	t.Helper()
	return openStoreAt(t, filepath.Join(t.TempDir(), FileName))
}

// openStoreAt opens a database at an exact path, for the tests that need two stores on one
// file or need to look at the file afterwards.
func openStoreAt(t *testing.T, path string) *Store {
	t.Helper()
	store, err := Open(context.Background(), path)
	if err != nil {
		t.Fatalf("open %s: %v", path, err)
	}
	// Closing twice is harmless - database/sql returns nil for the second - so a test may
	// close early to prove something about the file on disk.
	t.Cleanup(func() { store.Close() })
	return store
}

// sampleSpec is a NodeSpec with something in every kind of field the daemon reconciles, so
// a round trip through storage proves more than "the generation survived".
func sampleSpec(generation uint64) *wisperpb.NodeSpec {
	return &wisperpb.NodeSpec{
		Generation: generation,
		IssuedAt:   timestamppb.New(noon),
		Workloads: []*wisperpb.Workload{{
			Id:          "wl-api",
			Kind:        wisperpb.WorkloadKind_WORKLOAD_KIND_APP,
			Name:        "api",
			Image:       "docker.io/library/node:24-alpine",
			ImageDigest: "sha256:1f0c",
			Entrypoint:  []string{"/usr/local/bin/node"},
			Command:     []string{"server.js", "--port", "8080"},
			WorkingDir:  "/srv/app",
			Env: []*wisperpb.EnvVar{
				{Name: "NODE_ENV", Value: "production"},
				{Name: "DATABASE_URL", Value: "postgres://api@127.0.0.1/api", Secret: true},
			},
			Limits: &wisperpb.ResourceLimits{
				NanoCpus:        500_000_000,
				MemoryBytes:     512 << 20,
				MemorySwapBytes: 512 << 20,
				PidsLimit:       256,
				DiskBytes:       2 << 30,
				NofileLimit:     8192,
			},
			Mounts: []*wisperpb.Mount{{
				VolumeId:   "vol-data",
				Kind:       wisperpb.MountKind_MOUNT_KIND_VOLUME,
				Target:     "/srv/app/data",
				QuotaBytes: 2 << 30,
			}},
			Ports: []*wisperpb.PortBinding{{
				ContainerPort: 8080,
				HostPort:      0,
				Protocol:      wisperpb.PortProtocol_PORT_PROTOCOL_TCP,
			}},
			Restart:          &wisperpb.RestartPolicy{Mode: wisperpb.RestartPolicyMode_RESTART_POLICY_MODE_UNLESS_STOPPED},
			Runtime:          wisperpb.ContainerRuntime_CONTAINER_RUNTIME_RUNSC,
			DesiredState:     wisperpb.DesiredState_DESIRED_STATE_RUNNING,
			TenantNetwork:    "wisper-tenant-7",
			ReadOnlyRootfs:   true,
			User:             "10001:10001",
			StopGraceSeconds: 30,
		}, {
			Id:           "wl-site",
			Kind:         wisperpb.WorkloadKind_WORKLOAD_KIND_SITE,
			Name:         "marketing",
			DesiredState: wisperpb.DesiredState_DESIRED_STATE_RUNNING,
			ReleaseId:    "dep-1042",
			Site:         &wisperpb.SiteOptions{SpaFallback: true, IndexFile: "index.html"},
		}},
		Routes: []*wisperpb.Route{{
			Domain:     "api.example.test",
			WorkloadId: "wl-api",
			Port:       8080,
			TlsMode:    wisperpb.TlsMode_TLS_MODE_ON_DEMAND,
			ForceHttps: true,
		}},
		Engines: []*wisperpb.DatabaseEngineSpec{{
			Engine:         wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES,
			Image:          "docker.io/library/postgres:17",
			ListenPort:     5432,
			AdminUsername:  "wisper",
			AdminPassword:  "not-a-real-password",
			DataVolumeId:   "vol-pg",
			MaxConnections: 200,
		}},
		Databases: []*wisperpb.DatabaseGrant{{
			Id:           "grant-9",
			Engine:       wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES,
			DatabaseName: "api",
			Username:     "api",
			QuotaBytes:   1 << 30,
		}},
		Cron: []*wisperpb.CronEntry{{
			Id:             "cron-nightly",
			WorkloadId:     "wl-api",
			Schedule:       "0 3 * * *",
			Timezone:       "Asia/Ho_Chi_Minh",
			Command:        []string{"node", "scripts/report.js"},
			TimeoutSeconds: 900,
		}},
		FileRoots: []*wisperpb.FileRoot{{
			Id:         "root-data",
			Kind:       wisperpb.FileRootKind_FILE_ROOT_KIND_VOLUME,
			WorkloadId: "wl-api",
			VolumeId:   "vol-data",
			Label:      "Data",
			Writable:   true,
			QuotaBytes: 2 << 30,
		}},
		Retention: &wisperpb.RetentionPolicy{
			KeepReleases:           5,
			KeepBuildWorkspaces:    2,
			ContainerLogMaxBytes:   16 << 20,
			ContainerLogMaxFiles:   3,
			OrphanUploadTtlSeconds: 86400,
		},
		ReconcileIntervalSeconds: 15,
	}
}

// sampleSession is an upload of a 10 MiB file in 1 MiB chunks.
func sampleSession(id string) UploadSession {
	return UploadSession{
		SessionID:     id,
		RootID:        "root-data",
		Path:          "uploads/photo.jpg",
		StagingPath:   "uploads/.partial/" + id,
		TotalBytes:    10 << 20,
		ChunkSize:     1 << 20,
		ContentSHA256: "6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b",
		Overwrite:     false,
		CreatedAt:     noon,
		UpdatedAt:     noon,
		ExpiresAt:     noon.Add(24 * time.Hour),
	}
}
