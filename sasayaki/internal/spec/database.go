package spec

import "github.com/furimeo/wisper/sasayaki/internal/wisperpb"

// EngineKind is which database engine. The two are never interchangeable: the dump format,
// the SQL that creates a user and the connection URI scheme all differ, so every value that
// touches a database carries it rather than inferring it from a port or an image name.
type EngineKind string

const (
	// EngineUnknown is an engine this binary does not implement. The dbengine package
	// reports it and creates nothing: a container started with the wrong client would
	// produce a database no dump can be restored into.
	EngineUnknown  EngineKind = "UNKNOWN"
	EnginePostgres EngineKind = "POSTGRES"
	EngineMySQL    EngineKind = "MYSQL"
)

// Engine is a shared engine container this node must be running, one per engine per node.
//
// Shared rather than a container per customer because an idle PostgreSQL costs 30-50 MB of
// RSS and a node hosts hundreds of customers; a few hundred private instances is the whole
// machine (design section 8.1). It is part of the spec rather than something the daemon
// decides for itself because the panel owns intent - two nodes quietly running different
// major versions is a backup that restores everywhere except where it is needed.
type Engine struct {
	Kind EngineKind
	// Pinned by the panel, digest or tag. The node never picks a version.
	Image string
	// Published on the node's private interface only. Customers reach it through the
	// connection string the panel shows them; it is never on the public address.
	ListenPort uint32
	// The superuser the node uses to create databases and roles. The panel generates the
	// password so it can display and rotate it; a secret the node invented would be one the
	// panel could neither show nor put in a backup job.
	AdminUsername string
	AdminPassword string
	// Where the data directory lives, as a volume id the node resolves under its state
	// root. An id and not a path, so a node rebuild keeps customer data in the same place.
	DataVolumeID string
	// Docker's NanoCPUs, the same unit as Limits.NanoCPUs.
	NanoCPUs    int64
	MemoryBytes int64
	// Connection ceiling for the whole shared container. Without it one customer's leaking
	// pool locks every other customer out.
	MaxConnections int32
}

// Grant is one customer's database and login on an engine.
//
// In the spec, so a daemon that lost its disk recreates the account rather than leaving an
// application unable to log in with credentials the panel is still displaying. The password
// is deliberately not here: it arrives once, in a ProvisionDatabase or a
// RotateDatabasePassword command, so that a secret is not on the wire on every generation.
type Grant struct {
	// The panel's id for this grant, stable across renames of the database itself.
	ID           string
	Engine       EngineKind
	DatabaseName string
	Username     string
	// Enforced by measurement and reported back: neither engine has a hard per-database
	// quota, so the panel gets over_quota and decides, rather than the node silently
	// dropping writes.
	QuotaBytes int64
	// The paid escape hatch from the shared engine: a private container for this grant
	// instead of a role in the shared one.
	Dedicated bool
	// PostgreSQL encoding or MySQL character set. Empty means the engine default; changing
	// it after a customer has data means a dump and a reload.
	Encoding string
}

func engineFromProto(message *wisperpb.DatabaseEngineSpec) Engine {
	return Engine{
		Kind:           engineKindFromProto(message.GetEngine()),
		Image:          message.GetImage(),
		ListenPort:     message.GetListenPort(),
		AdminUsername:  message.GetAdminUsername(),
		AdminPassword:  message.GetAdminPassword(),
		DataVolumeID:   message.GetDataVolumeId(),
		NanoCPUs:       message.GetNanoCpus(),
		MemoryBytes:    message.GetMemoryBytes(),
		MaxConnections: message.GetMaxConnections(),
	}
}

func grantFromProto(message *wisperpb.DatabaseGrant) Grant {
	return Grant{
		ID:           message.GetId(),
		Engine:       engineKindFromProto(message.GetEngine()),
		DatabaseName: message.GetDatabaseName(),
		Username:     message.GetUsername(),
		QuotaBytes:   message.GetQuotaBytes(),
		Dedicated:    message.GetDedicatedInstance(),
		Encoding:     message.GetEncoding(),
	}
}

func engineKindFromProto(value wisperpb.DatabaseEngine) EngineKind {
	switch value {
	case wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES:
		return EnginePostgres
	case wisperpb.DatabaseEngine_DATABASE_ENGINE_MYSQL:
		return EngineMySQL
	default:
		return EngineUnknown
	}
}

func engineKindToProto(value EngineKind) wisperpb.DatabaseEngine {
	switch value {
	case EnginePostgres:
		return wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES
	case EngineMySQL:
		return wisperpb.DatabaseEngine_DATABASE_ENGINE_MYSQL
	default:
		return wisperpb.DatabaseEngine_DATABASE_ENGINE_UNSPECIFIED
	}
}
