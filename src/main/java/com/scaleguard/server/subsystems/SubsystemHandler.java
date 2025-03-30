package com.scaleguard.server.subsystems;

import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.router.RouteTarget;

public interface SubsystemHandler {

    void publish(RouteTarget rt, JsonNode message);
}
