package spec

import (
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func TestFileRootKinds(t *testing.T) {
	cases := map[wisperpb.FileRootKind]FileRootKind{
		wisperpb.FileRootKind_FILE_ROOT_KIND_VOLUME:         FileRootVolume,
		wisperpb.FileRootKind_FILE_ROOT_KIND_SITE:           FileRootSite,
		wisperpb.FileRootKind_FILE_ROOT_KIND_UPLOAD_STAGING: FileRootUploadStaging,
		wisperpb.FileRootKind_FILE_ROOT_KIND_UNSPECIFIED:    FileRootUnknown,
		wisperpb.FileRootKind(88):                           FileRootUnknown,
	}
	for wire, want := range cases {
		if got := fileRootKindFromProto(wire); got != want {
			t.Errorf("%v became %q, want %q", wire, got, want)
		}
	}
}

func TestFileRootFromProto(t *testing.T) {
	root := fileRootFromProto(&wisperpb.FileRoot{
		Id:         "vol-1",
		Kind:       wisperpb.FileRootKind_FILE_ROOT_KIND_VOLUME,
		WorkloadId: "1e9d",
		VolumeId:   "vol-1",
		Label:      "api / data",
		Writable:   true,
		QuotaBytes: 1 << 30,
	})

	if root.ID != "vol-1" || root.Kind != FileRootVolume {
		t.Errorf("root = %+v", root)
	}
	if !root.Writable || root.QuotaBytes != 1<<30 {
		t.Errorf("root = %+v, want writable with a 1GiB quota", root)
	}
}

// A site's releases tree is never writable: an edit made in place would be silently
// reverted by the next deployment, which is worse than not being able to make it.
func TestSiteRootIsNotWritable(t *testing.T) {
	root := fileRootFromProto(&wisperpb.FileRoot{
		Id:       "1e9d",
		Kind:     wisperpb.FileRootKind_FILE_ROOT_KIND_SITE,
		Writable: false,
	})

	if root.Writable {
		t.Error("a site root arrived writable")
	}
}

// Nil and empty read identically, because the generated getters are nil-safe and this
// package adds no second way of being empty.
func TestFileRootFromProtoAcceptsNil(t *testing.T) {
	got := fileRootFromProto(nil)

	if got.ID != "" || got.Kind != FileRootUnknown || got.Writable {
		t.Errorf("a nil root gave %+v", got)
	}
	if got != fileRootFromProto(&wisperpb.FileRoot{}) {
		t.Error("nil and an empty message must read the same")
	}
}
