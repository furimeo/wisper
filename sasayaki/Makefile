# sasayaki - the wisper node daemon.
#
# The artefact is one static binary with no runtime dependencies, because installing a
# node has to be "copy a file and start it". That is what build-linux produces, and it
# is the only output that matters in production; `build` exists so a developer on
# Windows or macOS can compile and run the tests without a VM.

MODULE  := github.com/furimeo/wisper/sasayaki
BINARY  := sasayaki
DIST    := dist

# Overridable so a release pipeline can stamp an exact tag rather than whatever the
# working tree happens to be. The fallbacks make a plain `make build` honest instead of
# claiming to be a release.
VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
COMMIT  ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo unknown)
DATE    ?= $(shell date -u +%Y-%m-%dT%H:%M:%SZ)

# -s -w drop the symbol table and DWARF: a few megabytes off a binary that gets pulled
# over a customer's connection during install, and nothing debugs a release build by
# attaching to it anyway - the panel gets a doctor report and logs.
LDFLAGS := -s -w \
	-X $(MODULE)/internal/version.Number=$(VERSION) \
	-X $(MODULE)/internal/version.Commit=$(COMMIT) \
	-X $(MODULE)/internal/version.BuildDate=$(DATE)

# -trimpath keeps the build machine's directory layout out of the binary. It also makes
# the build reproducible, which is what lets the published SHA-256 mean anything.
GOFLAGS := -trimpath

ifeq ($(OS),Windows_NT)
	HOST_EXT := .exe
else
	HOST_EXT :=
endif

.PHONY: help build build-linux test vet fmt run-dev clean

help: ## List the targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  %-14s %s\n", $$1, $$2}'

build: ## Compile for this machine into dist/
	@mkdir -p $(DIST)
	go build $(GOFLAGS) -ldflags "$(LDFLAGS)" -o $(DIST)/$(BINARY)$(HOST_EXT) ./cmd/sasayaki
	@echo "built $(DIST)/$(BINARY)$(HOST_EXT) $(VERSION)"

build-linux: ## Compile the static linux/amd64 binary a node actually runs, and hash it
	@mkdir -p $(DIST)
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
		go build $(GOFLAGS) -ldflags "$(LDFLAGS)" -o $(DIST)/$(BINARY)-linux-amd64 ./cmd/sasayaki
	@# The installer verifies this before it writes anything, and the panel shows the
	@# same hash on screen so an administrator can compare it by eye (design §7.1).
	@cd $(DIST) && sha256sum $(BINARY)-linux-amd64 > $(BINARY)-linux-amd64.sha256
	@cat $(DIST)/$(BINARY)-linux-amd64.sha256

test: ## Run the tests
	go test ./...

vet: ## Static analysis
	go vet ./...

fmt: ## Format every Go file in place
	gofmt -s -w .

run-dev: ## Run the daemon against the local Docker socket, state under ./var/dev
	@mkdir -p var/dev
	@# --dev keeps it off systemd, out of /var/lib/wisper, and on runc: WSL2 runs its
	@# own kernel and runsc is not guaranteed to work there (design §13.5).
	go run ./cmd/sasayaki run --state-dir ./var/dev --config ./var/dev/node.json --dev

clean: ## Remove build output and local development state
	rm -rf $(DIST) var/dev
