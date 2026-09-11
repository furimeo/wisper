package rpc

import "testing"

func TestParseEndpointFillsInWhatAnOperatorLeftOut(t *testing.T) {
	cases := []struct {
		raw       string
		target    string
		server    string
		plaintext bool
	}{
		{"https://panel.example", "panel.example:443", "panel.example", false},
		{"https://panel.example:9443", "panel.example:9443", "panel.example", false},
		{"panel.example", "panel.example:443", "panel.example", false},
		{"panel.example:9443", "panel.example:9443", "panel.example", false},
		{"  https://panel.example/  ", "panel.example:443", "panel.example", false},
		{"http://127.0.0.1:9090", "127.0.0.1:9090", "127.0.0.1", true},
		{"http://panel.internal", "panel.internal:9090", "panel.internal", true},
		{"https://[2001:db8::1]:9443", "[2001:db8::1]:9443", "2001:db8::1", false},
	}

	for _, want := range cases {
		t.Run(want.raw, func(t *testing.T) {
			endpoint, err := ParseEndpoint(want.raw)
			if err != nil {
				t.Fatalf("ParseEndpoint(%q): %v", want.raw, err)
			}
			if endpoint.Target != want.target {
				t.Errorf("target = %q, want %q", endpoint.Target, want.target)
			}
			if endpoint.ServerName != want.server {
				t.Errorf("server name = %q, want %q", endpoint.ServerName, want.server)
			}
			if endpoint.Plaintext != want.plaintext {
				t.Errorf("plaintext = %v, want %v", endpoint.Plaintext, want.plaintext)
			}
		})
	}
}

// A bare host means https. Reading it as plaintext would silently throw away the pin,
// which is the one thing this endpoint is protected by.
func TestABareHostIsTls(t *testing.T) {
	endpoint, err := ParseEndpoint("panel.example")
	if err != nil {
		t.Fatalf("ParseEndpoint: %v", err)
	}
	if endpoint.Plaintext {
		t.Error("a bare host was read as plaintext")
	}
}

func TestParseEndpointRefusesWhatItCannotHonour(t *testing.T) {
	refused := []string{
		"",
		"   ",
		"ftp://panel.example",
		"https://panel.example/api",
		"https://panel.example?token=x",
		"https://operator:secret@panel.example",
		"https://",
	}
	for _, raw := range refused {
		t.Run(raw, func(t *testing.T) {
			if endpoint, err := ParseEndpoint(raw); err == nil {
				t.Errorf("ParseEndpoint(%q) = %+v, want an error", raw, endpoint)
			}
		})
	}
}

// node.json holds the string form, so it has to survive the round trip.
func TestEndpointStringParsesBack(t *testing.T) {
	for _, raw := range []string{"https://panel.example", "http://127.0.0.1:9090"} {
		first, err := ParseEndpoint(raw)
		if err != nil {
			t.Fatalf("ParseEndpoint(%q): %v", raw, err)
		}
		second, err := ParseEndpoint(first.String())
		if err != nil {
			t.Fatalf("ParseEndpoint(%q): %v", first.String(), err)
		}
		if first != second {
			t.Errorf("%+v became %+v after a round trip", first, second)
		}
	}
}
