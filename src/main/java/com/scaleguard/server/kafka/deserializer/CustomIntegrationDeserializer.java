package com.scaleguard.server.kafka.deserializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.kafka.models.StreamingRawData;
import org.apache.kafka.common.serialization.Deserializer;

import java.util.Map;

public class CustomIntegrationDeserializer implements Deserializer<StreamingRawData> {
	@Override
	public void configure(Map<String, ?> configs, boolean isKey) {
	}

	@Override
	public StreamingRawData deserialize(String topic, byte[] data) {
		ObjectMapper mapper = new ObjectMapper();
		StreamingRawData object = null;
		try {
			object = mapper.readValue(data, StreamingRawData.class);
		} catch (Exception exception) {
			System.out.println("Error in deserializing bytes " + exception);
		}
		return object;
	}

	@Override
	public void close() {
	}
}