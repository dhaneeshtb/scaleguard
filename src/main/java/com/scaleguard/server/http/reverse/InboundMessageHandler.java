package com.scaleguard.server.http.reverse;

import com.scaleguard.server.http.auth.AuthInfo;
import com.scaleguard.server.http.auth.AuthUtils;
import com.scaleguard.server.http.cache.CacheManager;
import com.scaleguard.server.http.cache.CachedResponse;
import com.scaleguard.server.http.cache.ChecksumKey;
import com.scaleguard.server.http.cache.InMemoryCacheLooker;
import com.scaleguard.server.http.cache.RequestCacheInfo;
import com.scaleguard.server.http.router.RouteTable;
import com.scaleguard.server.http.router.SourceSystem;
import com.scaleguard.server.http.router.TargetSystem;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Consumer;

public class InboundMessageHandler {
  static ChecksumKey checksumKey = new ChecksumKey();

  static  RouteTable routeTable = RouteTable.getInstance();
  static CacheManager cacheManager=InMemoryCacheLooker.getInstance();
  public TargetSystem matchTarget(ChannelHandlerContext ctx, Object msg) {
    SourceSystem ss = new SourceSystem();
    if (msg instanceof HttpRequest) {
      HttpRequest request = (HttpRequest) msg;
      ss.setBasePath(request.uri());
      HttpHeaders headers = request.headers();

      QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
      Map<String, List<String>> params = queryStringDecoder.parameters();
      String authorization=headers.get("Authorization");
      String lob=headers.get("lob");
      if(authorization==null || authorization.isEmpty()){
        authorization=params.get("access_token")!=null?params.get("access_token").get(0):null;
      }
      if(authorization!=null) {
        lob=Optional.ofNullable(AuthUtils.getAuthInfo(authorization)).orElse(new AuthInfo(null,null)).getLob();
        if(lob!=null) {
          ss.setJwtKeylookup("lob:" + lob);
        }
      }else if(lob!=null){
        ss.setJwtKeylookup("lob:" + lob);
      }

      ss.setHost(request.headers().get(HttpHeaderNames.HOST, "unknown"));
    }
    return routeTable.findTarget(ss);
  }

  public void handle(ChannelHandlerContext ctx, Object msg, Consumer<CachedResponse> consumer){
    CachedResponse cr = getCachedResponse(null,msg);


    if(cr.getResponse()!=null){


      synchronized (cr.getKey().intern()) {
        if (cr.getResponse() != null) {
          ((List<Object>) cr.getResponse()).forEach(s -> {
            FullHttpResponse ins = (FullHttpResponse) s;
            writeInboundFlush(ctx, ins);
          });
        }
      }
      return;
    }else {
      consumer.accept(cr);
    }
  }

  public void writeInboundFlush(final ChannelHandlerContext ctx, Object msg) {
    final Channel inboundChannel = ctx.channel();

    inboundChannel.writeAndFlush(msg);

//    .addListener(new ChannelFutureListener() {
//      @Override
//      public void operationComplete(ChannelFuture future) {
//        if (future.isSuccess()) {
//          ctx.channel().read();
//        } else {
//          future.channel().close();
//        }
//      }
//    });
  }

  public CachedResponse getCachedResponse(RequestCacheInfo rc,Object msg){
    return cacheManager.lookup(null,getKeyData(msg));
  }

  public String getKeyData(Object msg){
    final StringBuilder buf = new StringBuilder();
    String cacheKey =null;
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
      cacheKey = checksumKey.get(request.headers().get(HttpHeaderNames.HOST, "unknown")+request.uri());
    }
    return cacheKey;
  }

  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    final StringBuilder buf = new StringBuilder();
    if (msg instanceof HttpRequest) {
      HttpRequest request =  (HttpRequest) msg;
      buf.setLength(0);
      buf.append("WELCOME TO THE WILD WILD WEB SERVER\r\n");
      buf.append("===================================\r\n");
      buf.append("VERSION: ").append(request.protocolVersion()).append("\r\n");
      buf.append("HOSTNAME: ").append(request.headers().get(HttpHeaderNames.HOST, "unknown"))
          .append("\r\n");
      buf.append("REQUEST_URI: ").append(request.uri()).append("\r\n\r\n");
      HttpHeaders headers = request.headers();
      if (!headers.isEmpty()) {
        for (Map.Entry<String, String> h : headers) {
          CharSequence key = h.getKey();
          CharSequence value = h.getValue();
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
      appendDecoderResult(buf, request);
    }

    if (msg instanceof HttpContent) {
      HttpContent httpContent = (HttpContent) msg;

      ByteBuf content = httpContent.content();
      if (content.isReadable()) {
        buf.append("CONTENT: ");
        buf.append(content.toString(CharsetUtil.UTF_8));
        buf.append("\r\n");
        appendDecoderResult(buf, httpContent);
      }

      if (msg instanceof LastHttpContent) {
        buf.append("END OF CONTENT\r\n");

        LastHttpContent trailer = (LastHttpContent) msg;
        if (!trailer.trailingHeaders().isEmpty()) {
          buf.append("\r\n");
          for (CharSequence name : trailer.trailingHeaders().names()) {
            for (CharSequence value : trailer.trailingHeaders().getAll(name)) {
              buf.append("TRAILING HEADER: ");
              buf.append(name).append(" = ").append(value).append("\r\n");
            }
          }
          buf.append("\r\n");
        }
      }
    }
    System.out.println(buf.toString());
  }

  private static void appendDecoderResult(StringBuilder buf, HttpObject o) {
    DecoderResult result = o.decoderResult();
    if (result.isSuccess()) {
      return;
    }

    buf.append(".. WITH DECODER FAILURE: ");
    buf.append(result.cause());
    buf.append("\r\n");
  }

}