module github.com/jumpserver/chen

go 1.26.3

require (
	gitee.com/chunanyong/dm v1.8.23
	github.com/ClickHouse/clickhouse-go/v2 v2.49.0
	github.com/go-sql-driver/mysql v1.9.3
	github.com/ibmdb/go_ibm_db v0.5.4
	github.com/jackc/pgx/v5 v5.9.2
	github.com/jumpserver/wisp v0.2.12
	github.com/microsoft/go-mssqldb v1.11.2
	github.com/oceanbase/go-oceanbase-driver v1.7.0-oceanbase.1
	github.com/pganalyze/pg_query_go/v6 v6.2.5
	github.com/pingcap/tidb/pkg/parser v0.0.0-20241218021942-d13e52ed6e22
	github.com/shopspring/decimal v1.4.0
	github.com/sijms/go-ora/v2 v2.9.0
	go.yaml.in/yaml/v3 v3.0.5
	google.golang.org/grpc v1.79.3
	google.golang.org/protobuf v1.36.10
)

require (
	filippo.io/edwards25519 v1.1.0 // indirect
	github.com/ClickHouse/ch-go v0.74.0 // indirect
	github.com/andybalholm/brotli v1.2.6 // indirect
	github.com/cespare/xxhash/v2 v2.3.0 // indirect
	github.com/go-faster/city v1.0.1 // indirect
	github.com/go-faster/errors v0.7.1 // indirect
	github.com/golang-sql/civil v0.0.0-20220223132316-b832511892a9 // indirect
	github.com/golang-sql/sqlexp v0.1.0 // indirect
	github.com/golang/snappy v0.0.4 // indirect
	github.com/google/uuid v1.6.0 // indirect
	github.com/ibmruntimes/go-recordio/v2 v2.0.0-20240416213906-ae0ad556db70 // indirect
	github.com/jackc/pgpassfile v1.0.0 // indirect
	github.com/jackc/pgservicefile v0.0.0-20240606120523-5a60cdf6a761 // indirect
	github.com/jackc/puddle/v2 v2.2.2 // indirect
	github.com/klauspost/compress v1.19.1 // indirect
	github.com/paulmach/orb v0.13.0 // indirect
	github.com/pierrec/lz4/v4 v4.1.27 // indirect
	github.com/pingcap/errors v0.11.5-0.20240311024730-e056997136bb // indirect
	github.com/pingcap/failpoint v0.0.0-20240528011301-b51a646c7c86 // indirect
	github.com/pingcap/log v1.1.0 // indirect
	github.com/segmentio/asm v1.2.1 // indirect
	go.opentelemetry.io/otel v1.46.0 // indirect
	go.opentelemetry.io/otel/trace v1.46.0 // indirect
	go.uber.org/atomic v1.11.0 // indirect
	go.uber.org/multierr v1.11.0 // indirect
	go.uber.org/zap v1.28.0 // indirect
	golang.org/x/crypto v0.55.0 // indirect
	golang.org/x/net v0.58.0 // indirect
	golang.org/x/sync v0.22.0 // indirect
	golang.org/x/sys v0.47.0 // indirect
	golang.org/x/text v0.41.0 // indirect
	google.golang.org/genproto/googleapis/rpc v0.0.0-20251202230838-ff82c1b0f217 // indirect
	gopkg.in/natefinch/lumberjack.v2 v2.2.1 // indirect
)

// Fixed IBM v0.5.4 source with Go 1.26-safe CLI result buffers.
replace github.com/ibmdb/go_ibm_db => ./third_party/go_ibm_db
