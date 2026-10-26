package engine

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/jumpserver/chen/internal/jumpserver"
	"github.com/jumpserver/chen/internal/sqlservice"
)

// Each session has at most two catalog connections, separately budgeted from
// fixed SQL consoles and their cancellation pool. No USE/SET is issued here.
type catalogResource struct {
	db        *sql.DB
	slots     chan struct{}
	mu        sync.Mutex
	database  string
	cursorKey [32]byte
}
type catalogCursor struct {
	Version      int    `json:"v"`
	Session      string `json:"s"`
	Database     string `json:"d"`
	Operation    string `json:"o"`
	Schema       string `json:"n,omitempty"`
	Kind         string `json:"k,omitempty"`
	Query        string `json:"q,omitempty"`
	AfterSchema  string `json:"as,omitempty"`
	AfterName    string `json:"an,omitempty"`
	AfterOrdinal int    `json:"ao,omitempty"`
	Table        string `json:"t,omitempty"`
}
type catalogPage[T any] struct {
	Items      []T    `json:"items"`
	NextCursor string `json:"next_cursor,omitempty"`
	HasMore    bool   `json:"has_more"`
}
type catalogScope struct {
	operation, schema, kind, query, table, cursor string
	limit                                         int
}
type catalogRequest struct {
	session  *session
	resource *catalogResource
	conn     *sql.Conn
	ctx      context.Context
	database string
	scope    catalogScope
	after    catalogCursor
	done     func()
}

func parseCatalogScope(r *http.Request, operation string) (catalogScope, error) {
	s := catalogScope{operation: operation, limit: 100}
	allowed := map[string]bool{"cursor": true, "limit": true}
	switch operation {
	case "objects", "ai":
		allowed["schema"], allowed["kind"], allowed["q"] = true, true, true
	case "columns", "inspect", "ddl":
		allowed["schema"], allowed["table"] = true, true
	}
	for key, values := range r.URL.Query() {
		if !allowed[key] || len(values) != 1 {
			return s, errors.New("invalid_catalog_request")
		}
	}
	values := r.URL.Query()
	s.schema, s.kind, s.query, s.table, s.cursor = values.Get("schema"), values.Get("kind"), values.Get("q"), values.Get("table"), values.Get("cursor")
	for _, name := range []string{s.schema, s.query, s.table} {
		if len(name) > 256 || strings.ContainsRune(name, 0) || !utf8.ValidString(name) {
			return s, errors.New("invalid_catalog_request")
		}
	}
	if s.kind != "" && s.kind != "table" && s.kind != "view" {
		return s, errors.New("invalid_catalog_request")
	}
	if (operation == "columns" || operation == "inspect" || operation == "ddl") && s.table == "" {
		return s, errors.New("invalid_catalog_request")
	}
	if len(s.cursor) > 4096 {
		return s, errors.New("invalid_catalog_cursor")
	}
	if raw := values.Get("limit"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 {
			return s, errors.New("invalid_catalog_request")
		}
		s.limit = min(n, 200)
	}
	return s, nil
}

func (v *Server) beginCatalog(w http.ResponseWriter, r *http.Request, operation string) *catalogRequest {
	s := v.auth(w, r, false)
	if s == nil {
		return nil
	}
	scope, err := parseCatalogScope(r, operation)
	if err != nil {
		fail(w, 400, err.Error(), "Invalid catalog request or page cursor")
		return nil
	}
	if (operation == "columns" || operation == "inspect" || operation == "ddl") && s.grant.Config.Dialect == "postgresql" && scope.schema == "" {
		fail(w, 400, "invalid_catalog_request", "PostgreSQL columns require an explicit schema")
		return nil
	}
	s.mu.Lock()
	if s.closed || s.ctx.Err() != nil {
		s.mu.Unlock()
		fail(w, 410, "session_closed", "Session is closed")
		return nil
	}
	if s.catalog == nil {
		db, err := openDatabase(s.grant.Config, 2)
		if err != nil {
			s.mu.Unlock()
			fail(w, 502, "catalog_unavailable", "Unable to initialize catalog access")
			return nil
		}
		db.SetMaxIdleConns(1)
		db.SetConnMaxIdleTime(time.Minute)
		resource := &catalogResource{db: db, slots: make(chan struct{}, 2)}
		if _, err := rand.Read(resource.cursorKey[:]); err != nil {
			s.mu.Unlock()
			_ = db.Close()
			fail(w, 503, "catalog_unavailable", "Unable to initialize catalog access")
			return nil
		}
		s.catalog = resource
	}
	resource := s.catalog
	s.catalogOps.Add(1)
	s.mu.Unlock()
	finish := func() { s.catalogOps.Done() }
	select {
	case v.catalogSlots <- struct{}{}:
	default:
		finish()
		w.Header().Set("Retry-After", "1")
		fail(w, 429, "catalog_busy", "Catalog request capacity reached")
		return nil
	}
	globalFinish := finish
	finish = func() { <-v.catalogSlots; globalFinish() }
	select {
	case resource.slots <- struct{}{}:
	default:
		finish()
		w.Header().Set("Retry-After", "1")
		fail(w, 429, "catalog_busy", "Too many catalog requests for this session")
		return nil
	}
	globalAndLocalFinish := finish
	finish = func() { <-resource.slots; globalAndLocalFinish() }
	timeout := 3 * time.Second
	if operation == "inspect" || operation == "ai" {
		timeout = 12 * time.Second
	}
	if operation == "ddl" {
		timeout = 30 * time.Second
	}
	ctx, cancel := context.WithTimeout(r.Context(), timeout)
	stop := context.AfterFunc(s.ctx, cancel)
	s.grant.SetBusy(true)
	s.grant.Touch()
	baseFinish := finish
	finish = func() { stop(); cancel(); s.grant.SetBusy(false); baseFinish() }
	if err := s.grant.Check(); err != nil {
		finish()
		fail(w, 403, "session_unavailable", err.Error())
		return nil
	}
	conn, err := resource.db.Conn(ctx)
	if err != nil {
		finish()
		catalogError(w, r, s, err)
		return nil
	}
	connFinish := finish
	finish = func() { _ = conn.Close(); connFinish() }
	var current sqlservice.Context
	if s.grant.Config.Dialect == "postgresql" {
		err = conn.QueryRowContext(ctx, "SELECT pg_catalog.current_database()").Scan(&current.Database)
	} else {
		current, err = connectionContext(ctx, conn, s.grant.Config.Dialect, sqlservice.Context{Database: s.grant.Config.Database, Schema: s.grant.Config.Schema})
	}
	database := current.Database
	if err != nil {
		finish()
		catalogError(w, r, s, err)
		return nil
	}
	if database == "" {
		finish()
		fail(w, 409, "catalog_database_required", "Configure a database before browsing its catalog")
		return nil
	}
	// The server selected this database from the trusted connection config.
	// Preserve its canonical name, including lower_case_table_names behavior.
	resource.mu.Lock()
	if resource.database == "" {
		resource.database = database
	}
	sameDatabase := resource.database == database
	resource.mu.Unlock()
	if !sameDatabase {
		finish()
		fail(w, 409, "catalog_scope_changed", "Catalog database scope changed")
		return nil
	}
	request := &catalogRequest{session: s, resource: resource, conn: conn, ctx: ctx, database: database, scope: scope, done: finish}
	if scope.cursor != "" {
		after, err := resource.decodeCursor(scope.cursor, s.id, database, scope)
		if err != nil {
			finish()
			fail(w, 400, "invalid_catalog_cursor", "Page cursor does not match this catalog request")
			return nil
		}
		request.after = after
	}
	if err := v.catalogAudit(request, "requested", 0); err != nil {
		finish()
		fail(w, 503, "audit_persistence_failed", "Catalog access could not be audited")
		return nil
	}
	return request
}

func (v *Server) catalogAudit(req *catalogRequest, outcome string, count int) error {
	now := time.Now()
	// A timeout or session closure must still leave its result audit. The
	// session's catalogOps barrier keeps the grant alive until this bounded
	// persistence operation completes, even after its query context is canceled.
	ctx, cancel := context.WithTimeout(context.WithoutCancel(req.ctx), 3*time.Second)
	defer cancel()
	return v.provider.Record(ctx, req.session.grant, jumpserver.AuditRecord{ID: newID("meta_"), RiskClassified: true, SQL: "/* Chen catalog " + req.scope.operation + " */", Output: fmt.Sprintf("outcome=%s database=%q schema=%q table=%q kind=%s returned=%d", outcome, req.database, req.scope.schema, req.scope.table, req.scope.kind, count), StartedAt: now, FinishedAt: now})
}
func (v *Server) finishCatalog(w http.ResponseWriter, r *http.Request, req *catalogRequest, count int, err error) bool {
	if req.ctx.Err() != nil {
		err = req.ctx.Err()
	}
	outcome := "succeeded"
	if err != nil {
		outcome = "failed"
	}
	if auditErr := v.catalogAudit(req, outcome, count); auditErr != nil {
		fail(w, 503, "audit_persistence_failed", "Catalog result could not be audited")
		return false
	}
	if err != nil {
		catalogError(w, r, req.session, err)
		return false
	}
	req.session.mu.Lock()
	closed := req.session.closed
	req.session.mu.Unlock()
	if closed || req.session.ctx.Err() != nil {
		fail(w, 410, "session_closed", "Session is closed")
		return false
	}
	if err := req.session.grant.Check(); err != nil {
		fail(w, 403, "session_unavailable", err.Error())
		return false
	}
	return true
}
func catalogError(w http.ResponseWriter, r *http.Request, s *session, err error) {
	switch {
	case s.ctx.Err() != nil:
		fail(w, 410, "session_closed", "Session is closed")
	case errors.Is(err, sqlservice.ErrCatalogForbidden):
		fail(w, 403, "catalog_forbidden", "Catalog access is denied")
	case errors.Is(err, sqlservice.ErrCatalogNotFound):
		fail(w, 404, "catalog_not_found", "Object is unavailable or does not exist")
	case errors.Is(err, context.DeadlineExceeded):
		fail(w, 504, "catalog_timeout", "Catalog request timed out")
	case r.Context().Err() != nil:
		fail(w, 408, "request_canceled", "Catalog request was canceled")
	default:
		fail(w, 502, "catalog_failed", "Unable to read the authorized catalog")
	}
}

func (v *Server) catalogSchemas(w http.ResponseWriter, r *http.Request) {
	req := v.beginCatalog(w, r, "schemas")
	if req == nil {
		return
	}
	defer req.done()
	items, err := sqlservice.SchemaRows(req.ctx, req.conn, sqlservice.Dialect(req.session.grant.Config.Dialect), req.database, req.after.AfterName, req.scope.limit)
	type schemaItem struct {
		Name string `json:"name"`
	}
	result := struct {
		Database string `json:"database"`
		catalogPage[schemaItem]
	}{Database: req.database, catalogPage: catalogPage[schemaItem]{Items: []schemaItem{}}}
	if len(items) > req.scope.limit {
		result.HasMore = true
		items = items[:req.scope.limit]
	}
	for _, item := range items {
		result.Items = append(result.Items, schemaItem{item.Name})
	}
	if result.HasMore {
		result.NextCursor = req.resource.encodeCursor(req.session.id, req.database, req.scope, "", items[len(items)-1].Name, 0)
	}
	if v.finishCatalog(w, r, req, len(items), err) {
		writeJSON(w, 200, result)
	}
}
func (v *Server) catalogObjects(w http.ResponseWriter, r *http.Request) {
	req := v.beginCatalog(w, r, "objects")
	if req == nil {
		return
	}
	defer req.done()
	items, err := sqlservice.ObjectRows(req.ctx, req.conn, sqlservice.Dialect(req.session.grant.Config.Dialect), sqlservice.CatalogObjectRequest{Database: req.database, Schema: req.scope.schema, Kind: req.scope.kind, Query: req.scope.query, AfterSchema: req.after.AfterSchema, AfterName: req.after.AfterName, Limit: req.scope.limit})
	if items == nil {
		items = []sqlservice.CatalogObject{}
	}
	result := catalogPage[sqlservice.CatalogObject]{Items: items}
	if len(items) > req.scope.limit {
		result.HasMore = true
		result.Items = items[:req.scope.limit]
		last := result.Items[len(result.Items)-1]
		result.NextCursor = req.resource.encodeCursor(req.session.id, req.database, req.scope, last.Schema, last.Name, 0)
	}
	if v.finishCatalog(w, r, req, len(result.Items), err) {
		writeJSON(w, 200, result)
	}
}
func (v *Server) catalogColumns(w http.ResponseWriter, r *http.Request) {
	req := v.beginCatalog(w, r, "columns")
	if req == nil {
		return
	}
	defer req.done()
	items, err := sqlservice.ColumnRows(req.ctx, req.conn, sqlservice.Dialect(req.session.grant.Config.Dialect), sqlservice.CatalogColumnRequest{Database: req.database, Schema: req.scope.schema, Table: req.scope.table, AfterOrdinal: req.after.AfterOrdinal, Limit: req.scope.limit})
	result := catalogPage[sqlservice.Column]{Items: []sqlservice.Column{}}
	if len(items) > req.scope.limit {
		result.HasMore = true
		items = items[:req.scope.limit]
	}
	for _, item := range items {
		result.Items = append(result.Items, sqlservice.Column{Name: item.Name, DataType: item.DataType, Nullable: item.Nullable, Comment: item.Comment})
	}
	if result.HasMore {
		result.NextCursor = req.resource.encodeCursor(req.session.id, req.database, req.scope, "", "", items[len(items)-1].Ordinal)
	}
	if v.finishCatalog(w, r, req, len(items), err) {
		writeJSON(w, 200, result)
	}
}

func (c *catalogResource) encodeCursor(session, database string, scope catalogScope, afterSchema, afterName string, afterOrdinal int) string {
	value := catalogCursor{Version: 1, Session: session, Database: database, Operation: scope.operation, Schema: scope.schema, Kind: scope.kind, Query: scope.query, Table: scope.table, AfterSchema: afterSchema, AfterName: afterName, AfterOrdinal: afterOrdinal}
	data, _ := json.Marshal(value)
	mac := hmac.New(sha256.New, c.cursorKey[:])
	_, _ = mac.Write(data)
	return base64.RawURLEncoding.EncodeToString(data) + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}
func (c *catalogResource) decodeCursor(encoded, session, database string, scope catalogScope) (catalogCursor, error) {
	var value catalogCursor
	parts := strings.Split(encoded, ".")
	if len(parts) != 2 {
		return value, errors.New("invalid cursor")
	}
	data, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return value, err
	}
	signature, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return value, err
	}
	mac := hmac.New(sha256.New, c.cursorKey[:])
	_, _ = mac.Write(data)
	if !hmac.Equal(signature, mac.Sum(nil)) {
		return value, errors.New("invalid cursor")
	}
	if json.Unmarshal(data, &value) != nil || value.Version != 1 || value.Session != session || value.Database != database || value.Operation != scope.operation || value.Schema != scope.schema || value.Kind != scope.kind || value.Query != scope.query || value.Table != scope.table {
		return value, errors.New("cursor scope mismatch")
	}
	return value, nil
}
