## What this changes

<!-- One paragraph. What is different afterwards, from the point of view of somebody using
     the panel or running a node. -->

## Why

<!-- The reason, not the implementation. If it fixes an issue, link it. -->

## How it was verified

<!-- Delete what does not apply. "It compiles" is not verification. -->

- [ ] `cd panel && ./gradlew build`
- [ ] `cd panel/frontend && npx tsc --noEmit`
- [ ] `cd sasayaki && gofmt -l . && go vet ./... && GOOS=linux go build ./... && go test ./...`
- [ ] Opened the screen in a browser and used it
- [ ] Ran it against a real node

## Checklist

- [ ] One file, one feature; nothing over 500 lines
- [ ] No stubs: everything reachable works
- [ ] Tests next to the code, covering the failure this prevents
- [ ] Comments say *why*
- [ ] `docs/contracts/` updated if a seam moved
- [ ] User-visible strings go through `t()` and exist in both `en/` and `vi/`

## Anything a reviewer should push back on

<!-- The trade-off you are least sure about. Saying it here is faster than being asked. -->
