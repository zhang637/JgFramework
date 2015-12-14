package com.zhaidaosi.game.jgframework.connector;

import static io.netty.handler.codec.http.HttpHeaders.Names.HOST;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Timer;
import java.util.TimerTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zhaidaosi.game.jgframework.Boot;
import com.zhaidaosi.game.jgframework.Router;
import com.zhaidaosi.game.jgframework.common.BaseRunTimer;
import com.zhaidaosi.game.jgframework.common.excption.MessageException;
import com.zhaidaosi.game.jgframework.message.IBaseMessage;
import com.zhaidaosi.game.jgframework.message.InMessage;
import com.zhaidaosi.game.jgframework.message.MessageDecode;
import com.zhaidaosi.game.jgframework.message.MessageEncode;
import com.zhaidaosi.game.jgframework.message.WebSocketEncode;
import com.zhaidaosi.game.jgframework.model.entity.IBaseCharacter;
import com.zhaidaosi.game.jgframework.rsync.RsyncManager;
import com.zhaidaosi.game.jgframework.session.SessionManager;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.CharsetUtil;

public class ServiceConnector implements IBaseConnector {
	private static final Logger log = LoggerFactory.getLogger(ServiceConnector.class);
	private final InetSocketAddress localAddress;
	private NioEventLoopGroup bossGroup;
	private NioEventLoopGroup workerGroup;
	private ServerBootstrap bootstrap;
	private Timer timer;
	private final long period;
	private final String mode;
	private final Object lock = new Object();
	private int connectCount = 0;
	private long startTime;
	public static final String MODE_SOCKET = "socket";
	public static final String MODE_WEB_SOCKET = "websocket";
	public static final String WEB_SOCKET_PATH = "/websocket";

	private int heartbeatTime = Boot.getServiceHeartbeatTime();

	public long getStartTime() {
		return startTime;
	}

	public int getConnectCount() {
		return connectCount;
	}

	public ServiceConnector(int port, long period, String mode) {
		this.localAddress = new InetSocketAddress(port);
		this.period = period;
		this.mode = mode;
	}

	public void start() {
		if (bootstrap != null) {
			return;
		}
		bootstrap = new ServerBootstrap();
		bossGroup = new NioEventLoopGroup(1);

		if (Boot.getServiceThreadCount() > 0) {
			workerGroup = new NioEventLoopGroup(Boot.getServiceThreadCount());
		} else {
			workerGroup = new NioEventLoopGroup();
		}

		try {
			bootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class);

			switch (mode) {
			case MODE_SOCKET:
				bootstrap.childHandler(new SocketServerInitializer());
				break;
			case MODE_WEB_SOCKET:
				bootstrap.childHandler(new WebSocketServerInitializer());
				break;
			default:
				log.error("Service 运行模式设置错误,必须为" + MODE_SOCKET + "或" + MODE_WEB_SOCKET);
				return;
			}

			SessionManager.init();
			RsyncManager.init();
			timer = new Timer("SyncManagerTimer");
			timer.schedule(new MyTimerTask(), period, period);
			startTime = System.currentTimeMillis();
			bootstrap.bind(localAddress);
			log.info("Connect Service is running! port : " + localAddress.getPort());
		} catch (Exception e) {
			log.error(e.getMessage());
		}
	}

	@Override
	public void stop() {
		if (bootstrap == null) {
			return;
		}
		workerGroup.shutdownGracefully();
		bossGroup.shutdownGracefully();
		bootstrap = null;
		timer.cancel();
		timer = null;
		SessionManager.destroy();
		RsyncManager.run();
	}

	class SocketServerInitializer extends ChannelInitializer<SocketChannel> {
		@Override
		public void initChannel(SocketChannel ch) throws Exception {
			ChannelPipeline pipeline = ch.pipeline();
			if (!Boot.getDebug()) {
				pipeline.addLast(new IdleStateHandler(heartbeatTime, 0, 0));
			}
			pipeline.addLast(new StringEncoder(Boot.getCharset()));
			pipeline.addLast(new StringDecoder(Boot.getCharset()));
			pipeline.addLast(new MessageEncode());
			pipeline.addLast(new MessageDecode());
			pipeline.addLast(new ServiceChannelHandler());
		}
	}

	class WebSocketServerInitializer extends ChannelInitializer<SocketChannel> {
		@Override
		public void initChannel(SocketChannel ch) throws Exception {
			ChannelPipeline pipeline = ch.pipeline();
			if (!Boot.getDebug()) {
				pipeline.addLast(new IdleStateHandler(heartbeatTime, 0, 0));
			}
			pipeline.addLast(new HttpResponseEncoder(), new HttpRequestDecoder(), new HttpObjectAggregator(65536),
					new WebSocketEncode(), new ServiceChannelHandler());
		}
	}

	class MyTimerTask extends TimerTask {
		@Override
		public void run() {
			log.info("start sync ...");
			RsyncManager.run();
		}
	}

	class ServiceChannelHandler extends ChannelInboundHandlerAdapter {
		private WebSocketServerHandshaker handshake;
		private IBaseCharacter player;

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable t) throws Exception {
			String errorMsg = t.getMessage();
			if (!(t instanceof ClosedChannelException)) {
				Channel ch = ctx.channel();
				if (t instanceof MessageException) {
					log.error(errorMsg);
				} else if (t instanceof ReadTimeoutException) {
					log.error("强制关闭超时连接  => " + ch.remoteAddress());
				} else {
					log.error(errorMsg, t);
				}
				ch.close();
			}
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
			/* 心跳处理 */
			if (evt instanceof IdleStateEvent) {
				IdleStateEvent event = (IdleStateEvent) evt;
				if (event.state() == IdleState.READER_IDLE) {
					/* 读超时 */
					System.out.println("READER_IDLE 读超时");
					ctx.disconnect();
				} else if (event.state() == IdleState.WRITER_IDLE) {
					/* 写超时 */
					System.out.println("WRITER_IDLE 写超时");
				} else if (event.state() == IdleState.ALL_IDLE) {
					/* 总超时 */
					System.out.println("ALL_IDLE 总超时");
				}
			}
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			synchronized (lock) {
				connectCount++;
			}
			player = Boot.getPlayerFactory().getPlayer();
			player.sChannel(ctx.channel());
			//zhangyoulei@ channel绑定了用户
			ctx.channel().attr(IBaseConnector.PLAYER).set(player);
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			SessionManager.removeSession(ctx.channel());
			synchronized (lock) {
				connectCount--;
			}
			BaseRunTimer.showTimer();
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			long startTime = 0;
			if (BaseRunTimer.isActive()) {
				startTime = System.currentTimeMillis();
			}
			if (msg instanceof FullHttpRequest) {
				handleHttpRequest(ctx, (FullHttpRequest) msg);
			} else {
				handleWebSocketRequest(ctx, msg);
			}
			if (BaseRunTimer.isActive()) {
				long runningTime = System.currentTimeMillis() - startTime;
				BaseRunTimer.addTimer("ServiceConnector messageReceived run " + runningTime + " ms");
				BaseRunTimer.showTimer();
			}
		}

		@Override
		public void channelReadComplete(ChannelHandlerContext ctx) {
			ctx.flush();
		}

		private void handleWebSocketRequest(ChannelHandlerContext ctx, Object msg) throws Exception {
			InMessage inMsg = null;
			IBaseMessage rs = null;
			Channel ch = ctx.channel();
			if (msg instanceof WebSocketFrame) {
				if (msg instanceof CloseWebSocketFrame) {
					handshake.close(ctx.channel(), (CloseWebSocketFrame) msg);
					return;
				}
				if (msg instanceof PingWebSocketFrame) {
					ctx.write(new PongWebSocketFrame(((PingWebSocketFrame) msg).content().retain()));
					return;
				}
				if (!(msg instanceof TextWebSocketFrame)) {
					throw new UnsupportedOperationException(
							String.format("%s msg types not supported", msg.getClass().getName()));
				}
				inMsg = InMessage.getMessage(((TextWebSocketFrame) msg).text());
			} else if (msg instanceof IBaseMessage) {
				inMsg = (InMessage) msg;
			}

			boolean error = true;
			if (!SessionManager.isAuthHandler(inMsg)) {
				int result = SessionManager.checkSession(inMsg, ch);
				if (result != SessionManager.ADD_SESSION_ERROR) {
					error = false;
					if (result == SessionManager.ADD_SESSION_SUCC) {
						rs = Router.run(inMsg, ch);
					} else {
						rs = SessionManager.getWaitMessage(player);
					}
				}
			}

			if (error) {
				log.error("强制关闭没授权的连接  => " + ch.remoteAddress());
				ch.close();
			} else if (rs != null && ch.isWritable()) {
				ch.write(rs);
			}
		}

		private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
			// Handle a bad request.
			if (!req.getDecoderResult().isSuccess()) {
				sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST));
				return;
			}
			if (req.getMethod() != GET) {
				sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN));
				return;
			}
			if (!WEB_SOCKET_PATH.equals(req.getUri())) {
				sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN));
				return;
			}
			// Handshake
			WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(getWebSocketLocation(req),
					null, true);
			handshake = wsFactory.newHandshaker(req);
			if (handshake == null) {
				WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
			} else {
				handshake.handshake(ctx.channel(), req);
			}
		}

		private void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse res) {
			// Generate an error page if response getStatus code is not OK
			// (200).
			if (res.getStatus().code() != 200) {
				ByteBuf buf = Unpooled.copiedBuffer(res.getStatus().toString(), CharsetUtil.UTF_8);
				res.content().writeBytes(buf);
				buf.release();
				HttpHeaders.setContentLength(res, res.content().readableBytes());
			}

			// Send the response and close the connection if necessary.
			ChannelFuture f = ctx.channel().writeAndFlush(res);
			if (!HttpHeaders.isKeepAlive(req) || res.getStatus().code() != 200) {
				f.addListener(ChannelFutureListener.CLOSE);
			}
		}

		private String getWebSocketLocation(HttpRequest req) {
			return "ws://" + req.headers().get(HOST) + WEB_SOCKET_PATH;
		}
	}

}
