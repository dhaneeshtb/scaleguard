package com.scaleguard.server.kafka.models;

import java.io.Serializable;

public class TransformerInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    public boolean isSkipPreprocessing() {
        return skipPreprocessing;
    }

    public void setSkipPreprocessing(boolean skipPreprocessing) {
        this.skipPreprocessing = skipPreprocessing;
    }

    private boolean skipPreprocessing;

	public String getEntityName() {
		return entityName;
	}

	public void setEntityName(String entityName) {
		this.entityName = entityName;
	}

	public String getTransformerId() {
		return transformerId;
	}

	public void setTransformerId(String transformerId) {
		this.transformerId = transformerId;
	}

	/**
     * The entity name.
     */
    private String entityName;

    /**
     * The transformer id.
     */
    private String transformerId;

}

