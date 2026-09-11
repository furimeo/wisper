# Contracts

**Everything in this folder is binding.** These are not notes, proposals or a wiki. They
describe the seams where one person's code meets another's, and they exist because
wisper is written by many hands working in parallel on packages that never see each
other's source.

## The rule

- If a contract says a URL, a port, a table name, a function signature or a file name,
  use exactly that. Do not adapt it locally to make your package tidier.
- If a contract is wrong, **change the contract first**, in its own commit, and say so
  in the pull request. Then change the code. Never the other way round: a contract that
  documents what somebody already shipped is not a contract, it is a changelog.
- If you need a seam that is not written down here, write it down here. A convention
  that exists only in one package's head is the thing this folder was created to
  prevent.

## Why the friction is deliberate

The predecessor project failed with two languages, a REST surface nobody owned and a
daemon whose state lived in RAM. Every one of its worst bugs was two components
disagreeing about something neither had written down. The `.proto` files are one answer
to that - a disagreement there is a compile error. This folder is the answer for
everything a compiler cannot check: URL space, configuration keys, database conventions,
the shape of a command-line command.

## What is here

| File | Binds |
|---|---|
| `panel-configuration.md` | Ports, the `wisper.*` property namespace, database conventions, the tables the framework needs |
| `schema.md` | What each table is for, who may write which column, the invariants |
| `panel-http.md` | The URL space, the view-name-to-component convention, how forms submit and how errors come back |
| `panel-ports.md` | Every interface and class that crosses a Java package boundary, with its owner |
| `pages.md` | Every Inertia page: its component name, its controller, and the exact props with their TypeScript types |
| `node-spec.md` | How database rows become a `NodeSpec`, what a generation means, and what the daemon reports back |
| `sasayaki-commands.md` | The subcommands `cmd/sasayaki/main.go` dispatches to, and the exact Go signatures they must have |
| `codegen.md` | Where generated protobuf code lands on each side, and the options the `.proto` files must set |

## Shared files

Certain files belong to the whole repository and are not part of any package. Changing
one affects everyone, so they are written once and then left alone:

```
AGENTS.md                     .gitignore
panel/settings.gradle         panel/build.gradle         panel/gradlew*
panel/gradle/wrapper/*        panel/src/main/resources/application*.yml
panel/src/main/java/lhqm/furimeo/wisper/WisperApplication.java
panel/src/main/java/lhqm/furimeo/wisper/web/**
panel/src/main/java/lhqm/furimeo/wisper/migration/**
panel/frontend/package.json   panel/frontend/vite.config.ts
panel/frontend/tsconfig.json  panel/frontend/index.html
panel/frontend/src/main.tsx   panel/frontend/src/inertia/**  panel/frontend/src/styles.css
sasayaki/go.mod               sasayaki/Makefile
sasayaki/cmd/sasayaki/main.go sasayaki/internal/version/**
docs/contracts/**
```

They already carry the seams every package needs -
`SharedPropsContributor`, `HttpSecurityContribution`, `@ConfigurationPropertiesScan`.
If one of them is genuinely missing something, say so rather than editing around it.
