/*
 * Copyright 2021 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.scaleguard.server.dns;

import com.scaleguard.server.http.utils.AppProperties;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.dns.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class DnsServer {
    private static final Logger logger
            = LoggerFactory.getLogger(DnsServer.class);

    private static final int DNS_SERVER_PORT = 53;
    private static final int PUBLIC_DNS_SERVER_PORT = 53;
    private static final String PUBLIC_DNS_SERVER_HOST = AppProperties.get("dnsProxy");
    NioEventLoopGroup group = new NioEventLoopGroup(10);

    NioEventLoopGroup clientGroup = new NioEventLoopGroup(10);

    Bootstrap bootstrap = new Bootstrap();

    public static void main(String[] args)  {
        DnsServer server = new DnsServer();
        server.start();
    }

    private static List<DnsRecord> handleQueryResp(DefaultDnsResponse msg) {
        if (msg.count(DnsSection.QUESTION) > 0) {
            DnsQuestion question = msg.recordAt(DnsSection.QUESTION, 0);
            if(logger.isInfoEnabled()) {
                logger.debug("name: {}", question.name());
            }
        }
        List<DnsRecord> dnsList = new ArrayList<>();
        for (int i = 0, count = msg.count(DnsSection.ANSWER); i < count; i++) {
            dnsList.add(msg.recordAt(DnsSection.ANSWER, i));
        }
        return dnsList;
    }

    public Channel udpStart() {
        try {
            bootstrap.group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(new ChannelInitializer<NioDatagramChannel>() {
                        @Override
                        protected void initChannel(NioDatagramChannel nioDatagramChannel) throws Exception {
                            nioDatagramChannel.pipeline().addLast(new DatagramDnsQueryDecoder());
                            nioDatagramChannel.pipeline().addLast(new DNSChannelInboundHandler());
                            nioDatagramChannel.pipeline().addLast("encoder", new DatagramDnsResponseEncoder());
                        }
                    })
                    .option(ChannelOption.SO_BROADCAST, true);
            return bootstrap.bind(DNS_SERVER_PORT).channel();

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;

    }

    public Channel tcpStart() {
        ServerBootstrap dnsbootstrap = new ServerBootstrap().group(new NioEventLoopGroup(1),
                        new NioEventLoopGroup())
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<Channel>() {
                                  @Override
                                  protected void initChannel(Channel ch) throws Exception {
                                      ch.pipeline().addLast(new TcpDnsQueryDecoder(), new TcpDnsResponseEncoder(), new DNSChannelInboundHandler());
                                  }
                              }
                );
        return dnsbootstrap.bind(DNS_SERVER_PORT).channel();


    }

    public void start() {
        new Thread(() -> {
            Channel tcpChannel = tcpStart();
            logger.info("TCP Server started...");
            Channel udpChannel = udpStart();
            logger.info("UDP Server started...");

            try {
                udpChannel.closeFuture().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                tcpChannel.closeFuture().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();

    }

    // copy from TcpDnsClient.java

    class DNSChannelInboundHandler extends SimpleChannelInboundHandler<DnsQuery> {

        private void send(
                ChannelHandlerContext ctx, DnsQuery msg, DefaultDnsResponse response) {
            if (msg instanceof DatagramDnsQuery) {
                DatagramDnsQuery dq = (DatagramDnsQuery) msg;
                AddressedEnvelope<DnsResponse, InetSocketAddress> in = new DefaultAddressedEnvelope<>(response, dq.sender());
                ctx.channel().writeAndFlush(in);
            } else {
                ctx.channel().writeAndFlush(response);
            }
        }


        @Override
        protected void channelRead0(ChannelHandlerContext ctx,
                                    DnsQuery msg) throws Exception {
            DnsQuestion question = msg.recordAt(DnsSection.QUESTION);
            if (DNSAddressBook.isEntryExist(question.name())) {
                DefaultDnsResponse dr = DNSAddressBook.get(question.name(),msg);
                send(ctx, msg, dr);
            } else {
                clientQuery(msg, PUBLIC_DNS_SERVER_HOST, PUBLIC_DNS_SERVER_PORT, respMsg -> {
                    List<DnsRecord> records = handleQueryResp(respMsg);
                    DefaultDnsResponse dr = newResponse(msg, question, records);
                    send(ctx, msg, dr);
                });
            }
        }

        private DefaultDnsResponse newResponse(DnsQuery query,
                                               DnsQuestion question, List<DnsRecord> records) {
            DefaultDnsResponse response = new DefaultDnsResponse(query.id());
            response.addRecord(DnsSection.QUESTION, question);
            records.forEach(r -> {
                DnsRawRecord raw = (DnsRawRecord) r;
                DefaultDnsRawRecord queryAnswer = new DefaultDnsRawRecord(
                        question.name(),
                        raw.type(), raw.timeToLive(), Unpooled.wrappedBuffer(ByteBufUtil.getBytes(raw.content())));
                response.addRecord(DnsSection.ANSWER, queryAnswer);
            });
            return response;
        }

        private void clientQuery(DnsQuery dnsQuery, String ip, int port, Consumer<DefaultDnsResponse> consumer) throws InterruptedException {
            Bootstrap clientQueryGroup = new Bootstrap();
            clientQueryGroup.group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new TcpDnsQueryEncoder())
                                    .addLast(new TcpDnsResponseDecoder())
                                    .addLast(new SimpleChannelInboundHandler<DefaultDnsResponse>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, DefaultDnsResponse msg) {
                                            try {
                                                consumer.accept(msg);
                                            } finally {
                                                ctx.close();
                                            }
                                        }
                                    });
                        }
                    });
            final Channel ch = clientQueryGroup.connect(ip, port).sync().channel();
            ch.writeAndFlush(dnsQuery.retain()).sync();
            ch.closeFuture().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture channelFuture) throws Exception {
                    ch.close().sync();
                }
            });
        }


    }
}
