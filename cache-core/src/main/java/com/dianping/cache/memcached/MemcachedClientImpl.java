/**
 * Project: avatar-cache
 * 
 * File Created at 2010-7-12
 * $Id$
 * 
 * Copyright 2010 Dianping.com Corporation Limited.
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of
 * Dianping Company. ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Dianping.com.
 */
package com.dianping.cache.memcached;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.spy.memcached.AddrUtil;
import net.spy.memcached.MemcachedClient;

import com.dianping.cache.core.CacheClient;
import com.dianping.cache.core.CacheClientBuilder;
import com.dianping.cache.core.CacheClientConfiguration;
import com.dianping.cache.core.CacheConfiguration;
import com.dianping.cache.core.InitialConfiguration;
import com.dianping.cache.core.KeyAware;
import com.dianping.cache.core.Lifecycle;
import com.dianping.cache.ehcache.EhcacheClientImpl;
import com.dianping.cache.ehcache.EhcacheConfiguration;
import com.dianping.lion.client.ConfigCache;

/**
 * The memcached client implementation adaptor(sypmemcached)
 * 
 * @author guoqing.chen
 * @author danson.liu
 * 
 */
public class MemcachedClientImpl implements CacheClient, Lifecycle, KeyAware, InitialConfiguration {

	/**
	 * in milliseconds
	 */
	private static final long				DEFAULT_GET_TIMEOUT				= 100;

	private static final String				DUAL_RW_SWITCH_NAME				= "avatar-cache.dualrw.enabled";
	private static final String				HOTKEY_DISTRIBUTED_LOCK_TIME	= "avatar-cache.hotkey.locktime";
	private static final String				GET_TIMEOUT_KEY					= "avatar-cache.memcached.get.timeout";

	/**
	 * Memcached client unique key
	 */
	private String							key;

	/**
	 * Memcached client
	 */
	private MemcachedClient					readClient;

	private MemcachedClient					writeClient;

	private MemcachedClient					backupClient					= null;

	/**
	 * Spymemcached client configuration
	 */
	private MemcachedClientConfiguration	config;

	private static Class<?>					configCacheClass				= null;

	static {
		try {
			configCacheClass = Class.forName("com.dianping.lion.client.ConfigCache");
		} catch (ClassNotFoundException e) {
			configCacheClass = null;
		}
	}

	@Override
	public String getKey() {
		return key;
	}

	@Override
	public void setKey(String key) {
		this.key = key;
	}

	@Override
	public void init(CacheClientConfiguration config) {
		this.config = (MemcachedClientConfiguration) config;
	}

	@Override
	public void add(String key, Object value, int expiration, String category) {
		String reformedKey = reformKey(key);
		writeClient.add(reformedKey, expiration, value);
		if (needDualRW()) {
			backupClient.add(reformedKey, expiration, value);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T get(String key, String category) {
		String reformedKey = reformKey(key);
		Future<Object> future = readClient.asyncGet(reformedKey);
		T result = null;

		try {
			// use timeout to eliminate memcached servers' crash
			result = (T) future.get(getGetTimeout(), TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			result = null;
		}

		if (result == null && needDualRW()) {
			try {
				result = (T) backupClient.asyncGet(reformedKey).get(getGetTimeout(), TimeUnit.MILLISECONDS);
			} catch (Exception e1) {
				result = null;
			}
		}

		return result;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> Map<String, T> getBulk(Collection<String> keys, Map<String, String> categories) {
		if (keys == null || keys.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, String> needReformed = reform(keys);
		Future<Map<String, Object>> future = null;
		boolean hasReformed = needReformed != null && !needReformed.isEmpty();
		if (!hasReformed) {
			future = readClient.asyncGetBulk(keys);
		} else {
			Collection<String> reformedKeys = new HashSet<String>();
			for (String key : keys) {
				String reformedKey = needReformed.get(key);
				reformedKeys.add(reformedKey != null ? reformedKey : key);
			}
			keys = reformedKeys;
			future = readClient.asyncGetBulk(reformedKeys);
		}

		Map<String, T> result = null;

		try {
			// use timeout to eliminate memcached servers' crash
			result = (Map<String, T>) future.get(getGetTimeout(), TimeUnit.MILLISECONDS);

		} catch (Exception e) {
			result = null;
		}

		if (result == null && needDualRW()) {
			try {
				result = (Map<String, T>) backupClient.asyncGetBulk(keys).get(getGetTimeout(), TimeUnit.MILLISECONDS);
			} catch (Exception e) {
				result = null;
			}
		}

		if (result == null) {
			return null;
		}

		if (!hasReformed || result.isEmpty()) {
			return result;
		} else {
			Map<String, T> reformBack = new HashMap<String, T>(result.size());
			for (Entry<String, T> entry : result.entrySet()) {
				String originalKey = needReformed.get(entry.getKey());
				reformBack.put(originalKey != null ? originalKey : entry.getKey(), entry.getValue());
			}
			return reformBack;
		}
	}

	@Override
	public void remove(String key, String category) {
		String reformedKey = reformKey(key);
		writeClient.delete(reformedKey);
		if (needDualRW()) {
			backupClient.delete(reformedKey);
		}
	}

	@Override
	public void replace(String key, Object value, int expiration, String category) {
		String reformedKey = reformKey(key);
		writeClient.replace(reformedKey, expiration, value);
		if (needDualRW()) {
			backupClient.replace(reformedKey, expiration, value);
		}
	}

	@Override
	public void set(String key, Object value, int expiration, String category) {
		String reformedKey = reformKey(key);
		writeClient.set(reformedKey, expiration, value);
		if (needDualRW()) {
			backupClient.set(reformedKey, expiration, value);
		}
	}

	@Override
	public long decrement(String key, int amount, String category) {
		String reformedKey = reformKey(key);
		long result = writeClient.decr(reformedKey, amount);
		if (needDualRW()) {
			backupClient.decr(reformedKey, amount);
		}
		return result;
	}

	@Override
	public long increment(String key, int amount, String category) {
		String reformedKey = reformKey(key);
		long result = writeClient.incr(reformedKey, amount);
		if (needDualRW()) {
			backupClient.incr(reformedKey, amount);
		}
		return result;
	}

	private String reformKey(String key) {
		return key != null ? key.replace(" ", "@+~") : key;
	}

	private Map<String, String> reform(Collection<String> keys) {
		Map<String, String> keyMap = null;
		if (keys != null) {
			for (String key : keys) {
				if (key.contains(" ")) {
					keyMap = keyMap != null ? keyMap : new HashMap<String, String>(keys.size());
					String reformedKey = reformKey(key);
					keyMap.put(key, reformedKey);
					keyMap.put(reformedKey, key);
				}
			}
		}
		return keyMap;
	}

	@Override
	public void clear() {
		writeClient.flush();
		if (needDualRW()) {
			backupClient.flush();
		}
	}

	@Override
	public void shutdown() {
		readClient.shutdown();
		writeClient.shutdown();
		if (backupClient != null) {
			backupClient.shutdown();
		}
	}

	@Override
	public void start() {
		try {
			// use ketama to provide consistent node hashing
			ExtendedConnectionFactory connectionFactory = new ExtendedKetamaConnectionFactory();
			if (config.getTranscoder() != null) {
				connectionFactory.setTranscoder(config.getTranscoder());
			} else {
				// set transcoder to HessianTranscoder:
				// 1. fast
				// 2. Fixed bug in https://bugs.launchpad.net/play/+bug/503349
				connectionFactory.setTranscoder(new HessianTranscoder());
			}
			String servers = config.getServers();
			if (servers == null) {
				throw new RuntimeException("Server address must be specified.");
			}
			// 由于使用KvdbClientImpl上线会对线上环境影响很大，所以采取折衷方案，混合memcached和memcachedb客户端
			if (!servers.contains(",")) {
				// memcached
				String[] serverSplits = config.getServers().split("\\|");
				String mainServer = serverSplits[0].trim();
				String backupServer = serverSplits.length == 1 ? null : serverSplits[1].trim();
				MemcachedClient client = new MemcachedClient(connectionFactory, AddrUtil.getAddresses(mainServer));
				readClient = client;
				writeClient = client;
				if (backupServer != null) {
					backupClient = new MemcachedClient(connectionFactory, AddrUtil.getAddresses(backupServer));
				}
			} else {
				// kvdb
				String[] serverSplits = servers.split(" ");
				String writeServer = serverSplits[0].trim();
				String readServers = serverSplits.length == 1 ? writeServer : serverSplits[1].trim();
				readClient = new MemcachedClient(connectionFactory, AddrUtil.getAddresses(readServers));
				writeClient = new MemcachedClient(connectionFactory, AddrUtil.getAddresses(writeServer));
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public boolean isDistributed() {
		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.dianping.cache.core.CacheClient#get(java.lang.String, boolean)
	 */
	@SuppressWarnings("unchecked")
	@Override
	public <T> T get(String key, boolean isHot, String category) {
		T result = (T) get(key, category);
		if (isHot) {
			if (result == null) {
				Future<Boolean> future = writeClient.add(key + "_lock", getHotkeyLockTime(), true);
				Future<Boolean> backupFuture = needDualRW() ? backupClient
						.add(key + "_lock", getHotkeyLockTime(), true) : null;
				Boolean locked = null;
				try {
					locked = future.get(getGetTimeout(), TimeUnit.MILLISECONDS);
				} catch (Exception e) {
				}

				if (backupFuture != null && locked == null) {
					try {
						locked = backupFuture.get(getGetTimeout(), TimeUnit.MILLISECONDS);
					} catch (Exception e) {
					}
				}

				if (locked == null || !locked.booleanValue()) {
					result = (T) getLocalCacheClient().get(key, category);
				} else {
					result = null;
				}

			} else {
				getLocalCacheClient().set(key, result, 3600 * 24, category);
			}
		}
		return result;
	}

	private CacheClient getLocalCacheClient() {
		if (CacheConfiguration.getCache("web") != null) {
			return CacheClientBuilder.buildCacheClient("web", new EhcacheConfiguration());
		} else {
			CacheConfiguration.addCache("web", EhcacheClientImpl.class.getName());
			return CacheClientBuilder.buildCacheClient("web", new EhcacheConfiguration());
		}

	}

	private boolean needDualRW() {
		boolean needDualRW = false;
		if (configCacheClass != null) {
			try {
				needDualRW = ConfigCache.getInstance().getBooleanProperty(DUAL_RW_SWITCH_NAME);
			} catch (Throwable e) {
			}
		}
		return needDualRW && backupClient != null;
	}

	private int getHotkeyLockTime() {
		Integer lockTime = null;
		if (configCacheClass != null) {
			try {
				lockTime = ConfigCache.getInstance().getIntProperty(HOTKEY_DISTRIBUTED_LOCK_TIME);
			} catch (Throwable e) {
			}
		}
		return lockTime == null ? 30 : lockTime;
	}

	private long getGetTimeout() {
		Long timeout = null;
		if (configCacheClass != null) {
			try {
				timeout = ConfigCache.getInstance().getLongProperty(GET_TIMEOUT_KEY);
			} catch (Throwable e) {
			}
		}
		return timeout == null ? DEFAULT_GET_TIMEOUT : timeout;
	}

    /* (non-Javadoc)
     * @see com.dianping.cache.core.CacheClient#set(java.lang.String, java.lang.Object, int, boolean)
     */
    @Override
    public void set(String key, Object value, int expiration, boolean isHot, String category) {
        set(key, value, expiration, category);
    }

	/* (non-Javadoc)
	 * @see com.dianping.cache.core.CacheClient#remove(java.lang.String)
	 */
	@Override
	public void remove(String key) {
		remove(key, null);
	}
}
