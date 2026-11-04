package sqlservice

import "context"

// MetadataContext bounds metadata work without shortening an existing operation
// deadline for drivers whose cancellation discards the physical connection.
// Table metadata requires several network round trips; expiring a separate
// three-second budget can otherwise destroy a console after a successful read.
// Callers without an operation deadline retain the standard catalog bound.
func MetadataContext(ctx context.Context, dialect Dialect) (context.Context, context.CancelFunc) {
	if dialect == Dameng || dialect == DB2 {
		if _, bounded := ctx.Deadline(); bounded {
			return context.WithCancel(ctx)
		}
	}
	return context.WithTimeout(ctx, catalogTimeout)
}
