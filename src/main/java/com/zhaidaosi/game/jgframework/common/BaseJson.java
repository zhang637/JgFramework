package com.zhaidaosi.game.jgframework.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;

public class BaseJson {
	private static final Logger log = LoggerFactory.getLogger(BaseJson.class);

	public static <T> T JsonToObject(String jsonStr, Class<T> c) {
		log.info(jsonStr);
		return JSON.parseObject(jsonStr, c);
	}

	public static String ObjectToJson(Object obj) {
		return JSON.toJSONString(obj);

	}
}
