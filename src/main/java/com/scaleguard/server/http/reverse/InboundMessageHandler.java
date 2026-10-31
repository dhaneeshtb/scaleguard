package com.scaleguard.server.http.reverse;

import com.scaleguard.server.http.router.ClientIpResolver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.server.http.async.AsyncFlowDrivers;
import com.scaleguard.server.http.auth.AuthInfo;
import com.scaleguard.server.http.cache.*;
import com.scaleguard.server.http.router.*;
import com.scaleguard.server.http.utils.AppProperties;
import com.scaleguard.server.subsystems.SubsystemHandler;
import com.scaleguard.server.subsystems.SubsytemHandlers;
import com.scaleguard.utils.JSON;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.CharsetUtil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class InboundMessageHandler {
  static ChecksumKey checksumKey = new ChecksumKey();

  private String host;

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  static  RouteTable routeTable = RouteTable.getInstance();
  static CacheManager cacheManager=CacheManagers.get();

  static volatile Class tokenParser;
  static volatile Method authGetter;

  private static void ensureAuthInitialized() {
    if (authGetter == null) {
      synchronized (InboundMessageHandler.class) {
        if (authGetter == null) {
          try {
            Class<?> tp = Class.forName(AppProperties.get("TokenParser"));
            Method ag = tp.getMethod("getAuthInfo", String.class);
            tokenParser = tp;
            authGetter = ag; // publish last — volatile write
          } catch (Exception e) {
            throw new RuntimeException("Failed to load TokenParser: " + e.getMessage(), e);
          }
        }
      }
    }
  }

  private AuthInfo getAuthInfo(String token){
    ensureAuthInitialized();
    try {
      return (AuthInfo) authGetter.invoke(null,new Object[]{token});
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    } catch (InvocationTargetException e) {
      throw new RuntimeException(e);
    }catch (Exception e){
      return null;
    }
  }

  static final io.netty.util.AsciiString X_FORWARDED_FOR = io.netty.util.AsciiString.cached("X-Forwarded-For");
  private static final io.netty.util.AsciiString X_REAL_IP = io.netty.util.AsciiString.cached("X-Real-IP");
  private static final io.netty.util.AsciiString X_FORWARDED_HOST = io.netty.util.AsciiString.cached("X-Forwarded-Host");
  private static final io.netty.util.AsciiString X_FORWARDED_PROTO = io.netty.util.AsciiString.cached("X-Forwarded-Proto");
  private static final io.netty.util.AsciiString HTTPS = io.netty.util.AsciiString.cached("https");
  private static final io.netty.util.AsciiString HTTP = io.netty.util.AsciiString.cached("http");

  public RouteTarget matchTarget(ChannelHandlerContext ctx, Object msg,int port) {
    InetSocketAddress peerAddr = com.scaleguard.server.http.h3.Http3Listener.peer(ctx.channel());
    String peerIp = peerAddr != null ? peerAddr.getAddress().getHostAddress() : "unknown";
    String xff = msg instanceof HttpRequest r ? r.headers().get(X_FORWARDED_FOR) : null;
    return matchTarget(ctx, msg, port, peerIp, xff, ClientIpResolver.resolve(peerIp, xff));
  }

  /** Hot path: the caller already knows the peer, its X-Forwarded-For and the resolved client address. */
  public RouteTarget matchTarget(ChannelHandlerContext ctx, Object msg, int port, String peerIp, String incomingXff, String clientIp) {
    return matchTarget(ctx, msg, String.valueOf(port), peerIp, incomingXff, clientIp);
  }

  /** Hot path. {@code port} is the listener port as text (the caller keeps it per connection). */
  public RouteTarget matchTarget(ChannelHandlerContext ctx, Object msg, String port, String peerIp, String incomingXff, String clientIp) {
    // The request-side key map (header / token values) is only built when some route selects on it.
    SourceSystem ss = null;
    String inAddress = peerIp;
    HttpHeaders headers =null;
    String uri=null, host = null;
    if (msg instanceof HttpRequest) {

      HttpRequest request = (HttpRequest) msg;

      if(request.uri().equalsIgnoreCase("/health")){
        return null;
      }
      if(request.uri().equalsIgnoreCase("/healthz")){
        return null;
      }
      if(request.uri().equalsIgnoreCase("/info")){
        return null;
      }
      if(request.uri().equalsIgnoreCase("/stats")){
        return null;
      }
      if(request.uri().equalsIgnoreCase("/config")){
        return null;
      }
      if(request.uri().equalsIgnoreCase("/metrics")){
        return null;
      }

      if(request.uri().startsWith("/.well-known")){
        return null;
      }

      if(request.uri().contains("?scaleguard=true")){
        return null;
      }

      headers = request.headers();

      // Routes can select on header or token values ("key lookup"). Building that map means decoding the
      // query string, parsing the token and copying every header, so it is only done when some route uses it.
      boolean keyLookup = routeTable.hasKeyLookupRoutes();
      if (keyLookup) {
        ss = new SourceSystem();
        final SourceSystem kss = ss;
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
        Map<String, List<String>> params = queryStringDecoder.parameters();
        String authorization=headers.get("Authorization","").toString();
        if(authorization==null || authorization.isEmpty()){
          authorization=params.get("access_token")!=null?params.get("access_token").get(0):null;
        }
        if(authorization!=null) {
          Map<String,Object> keys= Optional.ofNullable(getAuthInfo(authorization)).orElse(new AuthInfo(null,Map.of())).getKeys();
          if(keys!=null){
            keys.forEach((k,v)-> kss.setJwtKeylookup(k+":"+v));
          }
        }
      }

      // Real client IP (X-Forwarded-For only trusted from known proxies), and the header we pass upstream:
      // the trusted incoming chain plus our own peer — a client can't inject a fake origin.
      inAddress = clientIp;
      headers.set(X_FORWARDED_FOR, ClientIpResolver.forwardedFor(peerIp, incomingXff));
      headers.set(X_REAL_IP, inAddress);

      String hostHeader = headers.get(HttpHeaderNames.HOST);
      if (hostHeader != null) {
        headers.set(X_FORWARDED_HOST, hostHeader);
        headers.set(X_FORWARDED_PROTO, com.scaleguard.server.http.h2.ProtocolPipelines.isTls(ctx.channel()) ? HTTPS : HTTP);
      }


      if (keyLookup) {
        final SourceSystem kss = ss;
        headers.forEach(h->kss.setJwtKeylookup(h.getKey()+":"+h.getValue()));
      }
      uri=request.uri();
      host = hostHeader != null ? hostHeader : "unknown";
    }
    RouteTarget rt= uri == null ? null : routeTable.findTarget(host, port, uri, ss != null ? ss.getKeyLookupMap() : null);
    // OAuth token endpoints are served by the gateway itself on hosts it protects (and on unrouted hosts).
    if (uri != null && com.scaleguard.server.security.oauth.OAuthServer.handlesUri(uri)
            && (rt == null || (rt.getSourceSystem().getSecappid() != null && !rt.getSourceSystem().getSecappid().isEmpty()))) {
      return null;
    }
    if(rt!=null){
      rt.setClientIp(inAddress);
      rt.setHeaders(headers);
      rt.setUri(uri);
    }
    return rt;
  }

  public void reset(Object msg, Map<String, String> includeHeaders){
    if (msg instanceof HttpRequest) {
      HttpRequest request = (HttpRequest) msg;
      request.headers().set(HttpHeaderNames.HOST, host);
      if(includeHeaders!=null){
        includeHeaders.forEach((k,v)->request.headers().set(k, v));
      }
    }
  }

  /**
   * Thread-safe host-rewrite: sets the Host header to {@code targetHost} without
   * touching the shared {@link #host} field.
   *
   * <p>The original {@link #reset(Object, Map)} method mutates an instance field on a
   * <em>static</em> shared handler, creating a data race when two Netty I/O threads
   * process different client connections concurrently. This overload receives the
   * target host as a parameter and writes it directly to the request — no shared state.</p>
   */
  public void reset(Object msg, String targetHost, Map<String, String> includeHeaders) {
    if (msg instanceof HttpRequest) {
      HttpRequest request = (HttpRequest) msg;
      if (targetHost != null && !targetHost.isEmpty()) {
        request.headers().set(HttpHeaderNames.HOST, targetHost);
      }
      if (includeHeaders != null) {
        includeHeaders.forEach((k, v) -> request.headers().set(k, v));
      }
    }
  }



  public void handle(ChannelHandlerContext ctx, Object msg,RouteTarget ts, Consumer<CachedResponse> consumer){
    CachedResponse cr = getCachedResponse(ts,msg);
    if(cr!=null && cr.getResponse()!=null){
        if (cr.getResponse() != null) {
          ((List<Object>) cr.getResponse()).forEach(s -> {
            FullHttpResponse ins = (FullHttpResponse) s;
            writeInboundFlush(ctx, ins);
            RouteLogger.log(ts,true);
          });
        }
    }else {
      if(cr!=null && cr.getResource()!=null && cr.getResource().isAsync()){
        writeResponse(ctx, toResponse(cr.getProxyRequest()).toString());
      }else {
        consumer.accept(cr);
      }
    }
  }

  public void handleAsync(ChannelHandlerContext ctx, Object msg,RouteTarget ts, Consumer<CachedResponse> consumer){
    ProxyRequest pr=toProxyRequest(ts.getTargetSystem(),msg);
    ObjectNode node = JSON.object();

    if(ts.getSourceSystem().getAsyncEngine()!=null) {
      ProxyResponse response = Objects.requireNonNull(AsyncFlowDrivers.get(ts.getSourceSystem().getAsyncEngine())).publish(pr);
      try {
        writeResponse(ctx, LocalSystemLoader.mapper.writeValueAsString(response));
      } catch (JsonProcessingException e) {
        node.put("message","error while processing messsage :"+e.getMessage());
        writeResponse(ctx,HttpResponseStatus.INTERNAL_SERVER_ERROR,node.toString());
      }
    }else{
      node.put("message","no async engine configured");
      writeResponse(ctx,HttpResponseStatus.INTERNAL_SERVER_ERROR,node.toString());
    }
  }

  public void handleSubSystem(ChannelHandlerContext ctx, Object msg,RouteTarget ts, Consumer<CachedResponse> consumer){
    ObjectNode node = JSON.object();
    try {
      ProxyRequest pr=toProxyRequest(ts.getTargetSystem(),msg);
      String scheme = ts.getTargetSystem().getScheme();
      node.put("scheme",scheme);

      switch (scheme){
        case "kafka":
          SubsystemHandler subsystemHandler= SubsytemHandlers.get(ts);
          String topic=subsystemHandler.publish(ts, JSON.parse(pr.getBody()));
          node.put("topic",topic);
          break;
        default:
          break;
      }

      node.put("status","published");
      writeResponse(ctx,node.toString());
    }catch (Exception e){
      node.put("message","Error while publishing: "+e.getMessage());
      writeResponse(ctx,HttpResponseStatus.INTERNAL_SERVER_ERROR,node.toString());
    }
  }

  public ProxyRequest toProxyRequest(TargetSystem ts,Object msg){
    ProxyRequest pr = new ProxyRequest();
    Map<String,String> headerMap=new HashMap<>();
    if (msg instanceof HttpRequest) {
      HttpRequest request =  (HttpRequest) msg;
      HostGroup hg = ts.getHostGroup();
      if (hg != null) {
        pr.setPort(hg.getPort());
        pr.setHostGrpId(hg.getGroupId());
        pr.setHost(hg.getHost());
      } else {
        // Fallback when all cluster hosts are unreachable
        pr.setPort(ts.getPort());
        pr.setHostGrpId(ts.getGroupId());
        pr.setHost(ts.getHost());
      }
      pr.setScheme(ts.getScheme());
      pr.setUri((ts.getBasePath() != null && !ts.getBasePath().trim().equals("/") ? ts.getBasePath().trim() : "")+request.uri());
      request.headers().forEach((k)->headerMap.put(k.getKey(),k.getValue()));
      pr.setMethod(request.method().name().toString());
      pr.setId(UUID.randomUUID().toString());
      pr.setBody(parseJosnRequest((FullHttpRequest) msg));
      pr.setHeaders(headerMap);
    }
    return pr;
  }

  private JsonNode toResponse(ProxyRequest pr){
    ProxyResponse prs = new ProxyResponse();
    prs.setId(pr.getId());
    prs.setStatus("pending");
    return LocalSystemLoader.mapper.valueToTree(prs);
  }

  private String parseJosnRequest(FullHttpRequest request){
    ByteBuf jsonBuf = request.content();
    String jsonStr = jsonBuf.toString(CharsetUtil.UTF_8);
    return jsonStr;
  }

  private void writeResponse(ChannelHandlerContext ctx,
                             String responseData) {
    writeResponse(ctx,HttpResponseStatus.OK,responseData);
  }

  private void writeResponse(ChannelHandlerContext ctx,HttpResponseStatus status,
                             String responseData) {
    boolean keepAlive=false;
    FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
            status ,
            Unpooled.copiedBuffer(responseData, CharsetUtil.UTF_8));
    httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
    if (keepAlive) {
      httpResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,
              httpResponse.content().readableBytes());
      httpResponse.headers().set(HttpHeaderNames.CONNECTION,
              HttpHeaderValues.KEEP_ALIVE);
    }
    ctx.write(httpResponse);
    if (!keepAlive) {
      ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }
  }

  public void writeInboundFlush(final ChannelHandlerContext ctx, Object msg) {
    final Channel inboundChannel = ctx.channel();

    inboundChannel.writeAndFlush(msg).addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) {

        if (future.isSuccess()) {
          ctx.channel().read();
        } else {
          future.channel().close();
        }
      }
    });
  }

  public CachedResponse getCachedResponse(RouteTarget ts,Object msg){
    CachedResponse key = getKeyData(ts.getTargetSystem(),msg);
    if(key!=null) {
      CachedResponse crr = key.getKey()!=null? cacheManager.lookup(ts.getTargetSystem(), key.getKey()):key;
      crr.setResource(key.getResource());
      return crr;
    }
    return null;
  }

  public CachedResponse getKeyData(TargetSystem ts,Object msg){
    final StringBuilder buf = new StringBuilder();
    CachedResponse cr=null;
    Map<String,String> headerMap=new HashMap<>();
    if (msg instanceof HttpRequest) {
      HttpRequest request =  (HttpRequest) msg;
      buf.setLength(0);
      buf.append("VERSION: ").append(request.protocolVersion()).append("\r\n");
      buf.append("HOSTNAME: ").append(request.headers().get(HttpHeaderNames.HOST, "unknown"))
          .append("\r\n");
      buf.append("REQUEST_URI: ").append(request.uri()).append("\r\n\r\n");
      HttpHeaders headers = request.headers();
      if (!headers.isEmpty()) {
        for (Map.Entry<String, String> h : headers) {
          CharSequence key = h.getKey();
          CharSequence value = h.getValue();
          headerMap.put(key+"",value+"");
          buf.append("HEADER: ").append(key).append(" = ").append(value).append("\r\n");
        }
        buf.append("\r\n");
      }
      QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
      Map<String, List<String>> params = queryStringDecoder.parameters();
      if (!params.isEmpty()) {
        for (Entry<String, List<String>> p : params.entrySet()) {
          String key = p.getKey();
          List<String> vals = p.getValue();
          for (String val : vals) {
            buf.append("PARAM: ").append(key).append(" = ").append(val).append("\r\n");
          }
        }
        buf.append("\r\n");
      }

      List<CachedResource> crs= ts.getCachedResources().stream().filter(crx->crx.getMethod().equalsIgnoreCase(request.method().name().toString()) && request.uri().split("[?]")[0].startsWith(crx.getPattern())).collect(Collectors.toList());
      if(crs.size()>0) {
        CachedResource cachedResource=crs.get(0);
        cr=new CachedResponse();
        cr.setResource(cachedResource);
        if(cachedResource.isAsync()){
          ProxyRequest pr = new ProxyRequest();
          pr.setPort(ts.getPort());
          pr.setHost(ts.getHost());
          pr.setUri(request.uri());
          pr.setMethod(request.method().name().toString());
          pr.setId(UUID.randomUUID().toString());
          pr.setBody(parseJosnRequest((FullHttpRequest) msg));
          cr.setProxyRequest(pr);
        }
        if(cr.getResource().isCached()) {
          cr.setKey(toKey(cachedResource, request));
        }
      }
    }

    return cr;
  }

  private String toKey(CachedResource crs,HttpRequest request){
    ObjectNode onode= LocalSystemLoader.mapper.createObjectNode();
    onode.put("method",request.method().name());
    onode.put("uri",request.uri());
    onode.put("host",request.headers().get(HttpHeaderNames.HOST, "unknown"));
    Arrays.stream(crs.getKeyLookupHeaders() == null ? new String[0] : crs.getKeyLookupHeaders()).forEach(s->onode.put(s,request.headers().get(s)));
    return checksumKey.get(onode.toString());
  }

}