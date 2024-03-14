package com.scaleguard.server.http.reverse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.server.http.auth.AuthUtils;
import com.scaleguard.server.http.router.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class DefaultResponseHandler {

    private static String version = null;

    private static  HttpStaticFileServerHandler fileserveHandler = new HttpStaticFileServerHandler();

    private String parseJosnRequest(FullHttpRequest request) {
        ByteBuf jsonBuf = request.content();
        String jsonStr = jsonBuf.toString(CharsetUtil.UTF_8);
        return jsonStr;
    }

    public void handle(final ChannelHandlerContext ctx, SourceSystem sourceSystem, Object msg) {
        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;

            if (request.getMethod().name().equalsIgnoreCase("options")) {
                handleSuccess(ctx,"{}");
                return;

            }
            String body =null;
            if (request.getMethod().name().equalsIgnoreCase("post")) {
                body = parseJosnRequest((FullHttpRequest) request);
            }
            String uris[]=request.uri().split("\\?");
            String uri =uris[0];
            String params[] =uri.split("/");

            String token = request.headers().get("Authorization");
            boolean isValidToken = AuthUtils.isTokenValid(token);

            if(uri.startsWith("/adminui")){

                String fileDefault = null;

                if(!params[params.length-1].contains(".")){
                    fileDefault = "adminui/index.html";
                }
                try {
                    fileserveHandler.channelRead0(ctx, (FullHttpRequest) msg,fileDefault);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return;

            }else if (uri.startsWith("/health")) {

                handleHealth(ctx, sourceSystem);
                return;
            } else if (uri.startsWith("/info")) {
                handleInfo(ctx, sourceSystem);
                return;
            } else if (uri.startsWith("/stats")) {
                handleStats(ctx, sourceSystem);
                return;
            }  else if (uri.startsWith("/config")) {
                if(!isValidToken){
                    handleFailure(ctx,"no valid auth token",HttpResponseStatus.FORBIDDEN);
                    return;
                }
                String type = null;
                if (uri.contains("sourcesystems")) {
                    type = "sourcesystems";
                } else if (uri.contains("targetsystems")) {
                    type = "targetsystems";
                } else if (uri.contains("hostgroups")) {
                    type = "hostgroups";
                }

                if (request.getMethod().name().equalsIgnoreCase("post")||request.getMethod().name().equalsIgnoreCase("delete")) {
                    try {
                        ConfigManager.update(type,request.getMethod().name(),params, body);
                    } catch (Exception e) {
                        handleFailure(ctx, e.getMessage());
                        return;
                    }

                } else {
                    handleConfig(ctx,type,params);
                    return;
                }
                handleSuccess(ctx,"{}");
                return;
            }else {

                if(params.length>1 && RequestRoutingContexts.routesMap.containsKey(params[1])){


                    RequestRoute rr = RequestRoutingContexts.routesMap.get(params[1]);
                    if(rr.isAuthNeeded() && !isValidToken){
                        handleFailure(ctx, "no valid auth token", HttpResponseStatus.FORBIDDEN);
                        return;
                    }

                    try {
                      RequestRoutingResponse resp =   rr.handle(request.getMethod().name(), uri, body);
                      handleSuccess(ctx,resp.getBody());
                      return;
                    }catch (Exception e){
                        handleFailure(ctx,e.getMessage());
                        return;
                    }
                }
            }
        }

        ByteBuf content = Unpooled.copiedBuffer("Welcome to Scaleguard!", CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                //It is successful here, and does not represent the success of the customer, and brush out the data success default representative has completed
                if (future.isSuccess()) {
                    ctx.channel().read();
                } else {
                    future.channel().close();
                }
            }
        });

    }

    public void handleHealth(final ChannelHandlerContext ctx, SourceSystem sourceSystem) {
        ByteBuf content = Unpooled.copiedBuffer("{\"status\":\"healthy\"}", CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

        ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                //It is successful here, and does not represent the success of the customer, and brush out the data success default representative has completed
                if (future.isSuccess()) {
                    ctx.channel().read();
                } else {
                    future.channel().close();
                }
            }
        });
    }

    public void handleStats(final ChannelHandlerContext ctx, SourceSystem sourceSystem) {
        ByteBuf content = Unpooled.copiedBuffer(RouteLogger.toStatsJson().toString(), CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

        ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                //It is successful here, and does not represent the success of the customer, and brush out the data success default representative has completed
                if (future.isSuccess()) {
                    ctx.channel().read();
                } else {
                    future.channel().close();
                }
            }
        });
    }

    private String getId(String type,String[] params){
        String potentialId =params!=null? params[params.length-1]:null;
        if(type!=null && !type.equalsIgnoreCase(potentialId)){
            return potentialId;
        }
        return null;
    }

    public void handleConfig(final ChannelHandlerContext ctx,String type,String params[]) {
        ObjectNode finalObject = LocalSystemLoader.mapper.createObjectNode();
        if(type==null||"sourcesystems".equalsIgnoreCase(type)) {
            List<SourceSystem> ss =  RouteTable.getInstance().getSourceSystsems();
            String id = getId(type,params);
            if(id!=null){
                ss = ss.stream().filter(s->s.getId().equalsIgnoreCase(id)).collect(Collectors.toList());
            }
            JsonNode sourceSystems = LocalSystemLoader.mapper.valueToTree(ss);
            finalObject.set("sourcesystems", sourceSystems);
        }else if("targetsystems".equalsIgnoreCase(type)) {
            Collection<TargetSystem> ss =   RouteTable.getInstance().getTargetSystems().values();
            String id = getId(type,params);
            if(id!=null){
                ss = ss.stream().filter(s->s.getId().equalsIgnoreCase(id)).collect(Collectors.toList());
            }
            JsonNode sourceSystems = LocalSystemLoader.mapper.valueToTree(ss);
            finalObject.set("targetsystems", sourceSystems);
        }else if("hostgroups".equalsIgnoreCase(type)) {
            List<HostGroup> ss =   RouteTable.getInstance().getHostGroups();
            String id = getId(type,params);
            if(id!=null){
                ss = ss.stream().filter(s->s.getId().equalsIgnoreCase(id)).collect(Collectors.toList());
            }
            JsonNode sourceSystems = LocalSystemLoader.mapper.valueToTree(ss);
            finalObject.set("hostgroups", sourceSystems);
        }
        ByteBuf content = null;
        try {
            content = Unpooled.copiedBuffer(LocalSystemLoader.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(finalObject), CharsetUtil.UTF_8);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                //It is successful here, and does not represent the success of the customer, and brush out the data success default representative has completed
                if (future.isSuccess()) {
                    ctx.channel().read();
                } else {
                    future.channel().close();
                }
            }
        });
    }

    public void handleSuccess(final ChannelHandlerContext ctx,String message) {
        ByteBuf content = null;
        try {
            content = Unpooled.copiedBuffer(message, CharsetUtil.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "*");
        response.headers().set("Access-Control-Allow-Private-Network", "true");




        ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                //It is successful here, and does not represent the success of the customer, and brush out the data success default representative has completed
                if (future.isSuccess()) {
                    ctx.channel().read();
                } else {
                    future.channel().close();
                }
            }
        });
    }

    public void handleFailure(final ChannelHandlerContext ctx, String message) {
        handleFailure(ctx,message,HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }
    public void handleFailure(final ChannelHandlerContext ctx, String message,HttpResponseStatus code) {
        ByteBuf content = null;
        try {
            content = Unpooled.copiedBuffer("{\"message\":\"" + message + "\"}", CharsetUtil.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, code, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                //It is successful here, and does not represent the success of the customer, and brush out the data success default representative has completed
                if (future.isSuccess()) {
                    ctx.channel().read();
                } else {
                    future.channel().close();
                }
            }
        });
    }

    public void handleInfo(final ChannelHandlerContext ctx, SourceSystem sourceSystem) {
        if (version == null) {
            String content = "{}";
            try {
                content = Files.lines(Paths.get("version.json"))
                        .collect(Collectors.joining(System.lineSeparator()));
            } catch (IOException e) {
                e.printStackTrace();
            }
            version = content;
        }
        ByteBuf content = Unpooled.copiedBuffer(version, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                //It is successful here, and does not represent the success of the customer, and brush out the data success default representative has completed
                if (future.isSuccess()) {
                    ctx.channel().read();
                } else {
                    future.channel().close();
                }
            }
        });
    }
}
