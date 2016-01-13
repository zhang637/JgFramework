package com.zhaidaosi.game.jgframework.message;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

public class MessageDecode extends MessageToMessageDecoder<Object> {
	private static final Logger log = LoggerFactory.getLogger(MessageDecode.class);

	@Override
	protected void decode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
		if (msg instanceof String) {
			log.info(msg + "");
			msg = InMessage.getMessage((String) msg);
			out.add(msg);
		}
	}
}
