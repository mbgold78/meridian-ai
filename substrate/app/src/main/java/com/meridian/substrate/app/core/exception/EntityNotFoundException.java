package com.meridian.substrate.app.core.exception;

/**
 * Thrown when a requested entity cannot be found.
 */
public class EntityNotFoundException extends RuntimeException {

    private final String entityType;
    private final Object entityId;

    public EntityNotFoundException(String entityType, Object entityId) {
        super(entityType + " not found with id: " + entityId);
        this.entityType = entityType;
        this.entityId = entityId;
    }

    public String getEntityType() {
        return entityType;
    }

    public Object getEntityId() {
        return entityId;
    }
}
