package org.jumpserver.chen.framework.datasource.metadata;

import java.util.List;

/**
 * Generic vendor-specific properties not representable by the canonical model.
 * Order-preserving; used by Show Properties to avoid silently dropping fields.
 */
public record ObjectProperties(ObjectRef ref, List<PropertyItem> items) {
}
