package com.zhaidaosi.game.jgframework.message;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

public class MessageEncode extends MessageToMessageEncoder<Object> {
	private static final Logger log = LoggerFactory.getLogger(MessageEncode.class);

	@Override
	protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) {
		if (msg == null) {
			return;
		}
		if (msg instanceof IBaseMessage) {
			msg = msg.toString() + "\r\n";
			log.info(msg + "");
		}
		out.add(msg);
	}
}
