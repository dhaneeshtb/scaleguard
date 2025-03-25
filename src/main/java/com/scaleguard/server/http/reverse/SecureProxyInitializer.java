package com.scaleguard.server.http.reverse;

import com.scaleguard.server.http.router.RouteTarget;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import javax.net.ssl.SSLException;

public class SecureProxyInitializer extends ChannelInitializer<SocketChannel> {

	private String cacheKey;
	private Channel inbound;
	private final boolean isSecureBackend;
	static SslContext sslContext=null;

	RouteTarget rt;
	static{
		try {
			sslContext= SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
		} catch (SSLException e) {
			throw new RuntimeException(e);
		}

	}



	public SecureProxyInitializer(RouteTarget rt, Channel inbound, boolean isSecureBackend,String cacheKey){
		this.inbound = inbound;
		this.isSecureBackend = isSecureBackend;
		this.cacheKey=cacheKey;
		this.rt=rt;
	}


	@Override
	public void initChannel(SocketChannel ch) throws Exception {
		ChannelPipeline pipeline = ch.pipeline();

		// Add SSL handler first to encrypt and decrypt everything.
		// In this example, we use a bogus certificate in the server side
		// and accept any invalid certificates in the client side.
		// You will need something more complicated to identify both
		// and server in the real world.

		pipeline.addLast(new LoggingHandler(LogLevel.DEBUG));
		if (isSecureBackend) {

			pipeline.addLast(sslContext.newHandler(ch.alloc()));
		}
		pipeline.addLast(new HttpClientCodec());
		pipeline.addLast(new HttpObjectAggregator(Integer.MAX_VALUE));
		pipeline.addLast(new ScaleGuardBackendHandler(rt,inbound,cacheKey));
	}
}