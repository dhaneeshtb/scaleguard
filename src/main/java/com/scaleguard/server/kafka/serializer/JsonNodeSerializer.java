package com.scaleguard.server.kafka.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.kafka.models.StreamingRawData;
import org.apache.kafka.common.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class JsonNodeSerializer implements Serializer<JsonNode> {
	private static final Logger LOGGER = LoggerFactory.getLogger(JsonNodeSerializer.class);

	@Override
	public void configure(Map<String, ?> configs, boolean isKey) {
		//Handle
	}

	@Override
	public byte[] serialize(String topic, JsonNode data) {
		byte[] retVal = null;
		ObjectMapper objectMapper = new ObjectMapper();
		try {
			retVal = objectMapper.writeValueAsString(data).getBytes();
		} catch (Exception exception) {

			LOGGER.error("Error in serializing object" , exception);
		}
		return retVal;
	}

	@Override
	public void close() {
		//Handle

	}

}