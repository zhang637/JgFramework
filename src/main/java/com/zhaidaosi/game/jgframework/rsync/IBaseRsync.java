package com.zhaidaosi.game.jgframework.rsync;

import java.util.Map;

import com.zhaidaosi.game.jgframework.common.sdm.IBaseModel;

public interface IBaseRsync {

	void addRsync(Integer id, IBaseModel obj);

	void runRsync();

	void setRsyncMap(Map<Integer, IBaseModel> map);

	void clearRsyncMap();

	Map<Integer, IBaseModel> getNeedRsync();

	IBaseModel get(Integer id);

	void clearNeedRsync();

	boolean isRunning();

	void toRunning();

	void toStop();

}
