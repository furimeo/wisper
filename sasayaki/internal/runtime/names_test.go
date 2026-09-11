package runtime

import (
	"strings"
	"testing"
)

func TestCheckIdentifierAcceptsWhatThePanelSends(t *testing.T) {
	for _, value := range []string{"42", "blog-api", "vol_1", "a.b", "A1"} {
		if err := checkIdentifier("volume id", value); err != nil {
			t.Errorf("checkIdentifier(%q) = %v, want it accepted", value, err)
		}
	}
}

// Narrow on purpose. Everything the panel actually sends - a bigint id, a slug - fits
// inside this, so a value that does not is a bug or an attack, and the answer is to refuse
// rather than to sanitise: sanitising maps two different ids onto one directory.
func TestCheckIdentifierRefusesAnythingThatCouldBecomeAPath(t *testing.T) {
	for _, value := range []string{
		"", "..", ".", ".git", "a/b", `a\b`, "a b", "a:b", "a;b", "a$b", "a\nb", "a\x00b",
		strings.Repeat("x", maxIdentifier+1),
	} {
		if err := checkIdentifier("volume id", value); err == nil {
			t.Errorf("checkIdentifier(%q) was accepted", value)
		}
	}
}

func TestAContainerNameIsReadableAndUnique(t *testing.T) {
	name, err := containerName("42", "Blog API")
	if err != nil {
		t.Fatalf("containerName: %v", err)
	}
	if name != "wisper-blog-api-42" {
		t.Errorf("name = %q, want the service readable in docker ps with the id after it", name)
	}

	// Two services called the same thing in different projects are different containers.
	other, err := containerName("43", "Blog API")
	if err != nil {
		t.Fatalf("containerName: %v", err)
	}
	if other == name {
		t.Error("two workloads produced one container name")
	}

	// A name with nothing usable in it still produces a legal container name.
	odd, err := containerName("44", "***")
	if err != nil {
		t.Fatalf("containerName: %v", err)
	}
	if odd != "wisper-44" {
		t.Errorf("name = %q, want the prefix and the id", odd)
	}
}

func TestAContainerNameRefusesAWorkloadIdItCannotTrust(t *testing.T) {
	if _, err := containerName("../evil", "Blog"); err == nil {
		t.Fatal("a workload id that is a path fragment reached a container name")
	}
}

func TestSlugProducesSomethingDockerAndDnsBothAccept(t *testing.T) {
	cases := map[string]string{
		"Blog API":        "blog-api",
		"  spaced  out  ": "spaced-out",
		"Ünïcødé":         "n-c-d",
		"***":             "",
		"a-very-long-name-that-goes-on-and-on-and-on-forever": "a-very-long-name-that-goes-on-an",
	}
	for input, want := range cases {
		if got := slug(input); got != want {
			t.Errorf("slug(%q) = %q, want %q", input, got, want)
		}
	}
	for input := range cases {
		got := slug(input)
		if strings.HasPrefix(got, "-") || strings.HasSuffix(got, "-") {
			t.Errorf("slug(%q) = %q, which is not a legal hostname label", input, got)
		}
		if len(got) > 32 {
			t.Errorf("slug(%q) is %d characters", input, len(got))
		}
	}
}
