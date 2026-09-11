package spec

// The lookups every consumer of a Spec needs.
//
// They are here and not in each package because the alternative is five packages each
// writing their own loop over Spec.Workloads, and one of them eventually returning the
// first match where another returns the last. A spec holds tens of entries, not thousands,
// so a linear scan is the right shape: building an index would mean keeping it valid across
// a document that is replaced whole on every generation.

// Workload finds one workload by id.
func (s Spec) Workload(id string) (Workload, bool) {
	for _, workload := range s.Workloads {
		if workload.ID == id {
			return workload, true
		}
	}
	return Workload{}, false
}

// WorkloadIDs is every workload id, in spec order.
//
// The set the reconciler compares against what Docker reports: anything running under this
// node's management that is not in here is removed, which is the garbage-collection half of
// reconciliation.
func (s Spec) WorkloadIDs() []string {
	ids := make([]string, 0, len(s.Workloads))
	for _, workload := range s.Workloads {
		ids = append(ids, workload.ID)
	}
	return ids
}

// RoutesFor is every hostname pointing at one workload, in spec order.
//
// More than one is normal: a customer with an apex and a www alias has two, and one
// hostname per workload is the exception rather than the rule.
func (s Spec) RoutesFor(workloadID string) []Route {
	var routes []Route
	for _, route := range s.Routes {
		if route.WorkloadID == workloadID {
			routes = append(routes, route)
		}
	}
	return routes
}

// FileRoot finds one file root by id.
//
// The first call in every file operation: the request names a root and a relative path, and
// nothing may touch the filesystem until this has answered. A false return is a refusal,
// not a fallback to some default root.
func (s Spec) FileRoot(id string) (FileRoot, bool) {
	for _, root := range s.FileRoots {
		if root.ID == id {
			return root, true
		}
	}
	return FileRoot{}, false
}

// CronFor is every scheduled command that runs inside one workload, in spec order.
func (s Spec) CronFor(workloadID string) []CronEntry {
	var entries []CronEntry
	for _, entry := range s.Cron {
		if entry.WorkloadID == workloadID {
			entries = append(entries, entry)
		}
	}
	return entries
}

// Engine finds the shared engine container of one kind.
//
// At most one per engine per node, which is the whole point of sharing them, so this
// returns a single value rather than a list.
func (s Spec) Engine(kind EngineKind) (Engine, bool) {
	for _, engine := range s.Engines {
		if engine.Kind == kind {
			return engine, true
		}
	}
	return Engine{}, false
}

// GrantsFor is every customer database carved out of one engine, in spec order.
func (s Spec) GrantsFor(kind EngineKind) []Grant {
	var grants []Grant
	for _, grant := range s.Grants {
		if grant.Engine == kind {
			grants = append(grants, grant)
		}
	}
	return grants
}

// Grant finds one database grant by id.
func (s Spec) Grant(id string) (Grant, bool) {
	for _, grant := range s.Grants {
		if grant.ID == id {
			return grant, true
		}
	}
	return Grant{}, false
}
