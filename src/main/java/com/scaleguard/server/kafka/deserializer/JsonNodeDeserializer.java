package com.scaleguard.server.kafka.deserializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.kafka.models.StreamingRawData;
import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class JsonNodeDeserializer implements Deserializer<JsonNode> {
	private static final Logger LOGGER = LoggerFactory.getLogger(JsonNodeDeserializer.class);

	@Override
	public void configure(Map<String, ?> configs, boolean isKey) {
		//Configure Serializer
	}

	@Override
	public JsonNode deserialize(String topic, byte[] data) {
		ObjectMapper mapper = new ObjectMapper();
		try {
			return mapper.readTree(data);
		} catch (Exception exception) {
			LOGGER.error("Error in deserializing bytes ", exception);
		}
		return null;
	}

	@Override
	public void close() {
		//Handle close
	}
}