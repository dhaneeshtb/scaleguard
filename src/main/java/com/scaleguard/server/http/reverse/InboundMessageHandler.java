package com.scaleguard.server.http.reverse;

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
  static CacheManager cacheManager=InMemoryCacheLooker.getInstance();

  static Class tokenParser;

  static Method authGetter;

  static {
    try {
      tokenParser = Class.forName(AppProperties.get("TokenParser"));
      authGetter =  tokenParser.getMethod("getAuthInfo", String.class);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private AuthInfo getAuthInfo(String token){
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

  public RouteTarget matchTarget(ChannelHandlerContext ctx, Object msg,int port) {
    SourceSystem ss = new SourceSystem();
    String inAddress= ((InetSocketAddress)ctx.channel().remoteAddress()).getAddress().getHostAddress();
    HttpHeaders headers =null;
    String uri=null;
    if (msg instanceof HttpRequest) {

      HttpRequest request = (HttpRequest) msg;
      ss.setBasePath(request.uri());

      if(request.uri().equalsIgnoreCase("/health")){
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

      if(request.uri().contains("?scaleguard=true")){
        return null;
      }

      headers = request.headers();

      QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
      Map<String, List<String>> params = queryStringDecoder.parameters();
      String authorization=headers.get("Authorization","").toString();
      if(authorization==null || authorization.isEmpty()){
        authorization=params.get("access_token")!=null?params.get("access_token").get(0):null;
      }
      if(authorization!=null) {
        Map<String,Object> keys= Optional.ofNullable(getAuthInfo(authorization)).orElse(new AuthInfo(null,Map.of())).getKeys();
        if(keys!=null){
          keys.forEach((k,v)-> ss.setJwtKeylookup(k+":"+v));
        }
      }

      inAddress=headers.get("X-Forwarded-For",inAddress);

      if(request.headers().contains(HttpHeaderNames.HOST)) {
        headers.set("X-Forwarded-Host", request.headers().get(HttpHeaderNames.HOST));
        headers.set("X-Forwarded-Proto", ctx.pipeline().get(SslHandler.class) != null ? "https" : "http");
      }


      headers.forEach(h->ss.setJwtKeylookup(h.getKey()+":"+h.getValue()));
      ss.setHost(request.headers().get(HttpHeaderNames.HOST, "unknown").toString());
      ss.setPort(port+"");
      uri=request.uri();
    }
    RouteTarget rt= routeTable.findTarget(ss);
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
      pr.setPort(ts.getHostGroup().getPort());
      pr.setHostGrpId(ts.getHostGroup().getGroupId());
      pr.setScheme(ts.getScheme());
      pr.setHost(ts.getHostGroup().getHost());
      pr.setUri((ts.getBasePath().trim().equals("/")?"":ts.getBasePath().trim())+request.uri());
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
    Arrays.stream(crs.getKeyLookupHeaders()).forEach(s->onode.put(s,request.headers().get(s)));
    return checksumKey.get(onode.toString());
  }

}