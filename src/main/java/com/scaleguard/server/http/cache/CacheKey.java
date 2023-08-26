package com.scaleguard.server.http.cache;

import io.netty.handler.codec.http.FullHttpRequest;

public interface CacheKey {
  String get(Object msg);
}
