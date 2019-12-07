/**
 * Copyright 2009-2019 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.mapping;

import org.apache.ibatis.builder.InitializingObject;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;
import org.apache.ibatis.cache.decorators.*;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * @author Clinton Begin
 */
public class CacheBuilder {
    private final String id;
    private Class<? extends Cache> implementation;
    private final List<Class<? extends Cache>> decorators;
    private Integer size;
    private Long clearInterval;
    private boolean readWrite;
    private Properties properties;
    private boolean blocking;

    public CacheBuilder(String id) {
        this.id = id;
        this.decorators = new ArrayList<>();
    }

    public CacheBuilder implementation(Class<? extends Cache> implementation) {
        this.implementation = implementation;
        return this;
    }

    public CacheBuilder addDecorator(Class<? extends Cache> decorator) {
        if (decorator != null) {
            this.decorators.add(decorator);
        }
        return this;
    }

    public CacheBuilder size(Integer size) {
        this.size = size;
        return this;
    }

    public CacheBuilder clearInterval(Long clearInterval) {
        this.clearInterval = clearInterval;
        return this;
    }

    public CacheBuilder readWrite(boolean readWrite) {
        this.readWrite = readWrite;
        return this;
    }

    public CacheBuilder blocking(boolean blocking) {
        this.blocking = blocking;
        return this;
    }

    public CacheBuilder properties(Properties properties) {
        this.properties = properties;
        return this;
    }

    /**
     * 构建缓存实例
     *
     * @return
     */
    public Cache build() {
        // 设置默认的缓存类型(PerpetualCache)和缓存装饰器(LruCache)
        setDefaultImplementations();
        // 通过反射创建缓存
        Cache cache = newBaseCacheInstance(implementation, id);
        //将Cache标签的内置properties赋值到缓存对象属性上
        setCacheProperties(cache);
        // issue #352, do not apply decorators to custom caches
        // 仅对内置缓存 PerpetualCache 应用装饰器
        if (PerpetualCache.class.equals(cache.getClass())) {
            // 遍历装饰器集合，应用装饰器
            for (Class<? extends Cache> decorator : decorators) {
                // 通过反射创建装饰器实例
                cache = newCacheDecoratorInstance(decorator, cache);
                // 设置属性值到缓存实例中
                setCacheProperties(cache);
            }
            // 应用标准的装饰器，比如 LoggingCache、SynchronizedCache
            cache = setStandardDecorators(cache);
        } else if (!LoggingCache.class.isAssignableFrom(cache.getClass())) {
            //  对非 LoggingCache 类型的缓存应用 LoggingCache 装饰器 应用具有日志功能的缓存装饰器
            cache = new LoggingCache(cache);
        }
        return cache;
    }

    private void setDefaultImplementations() {
        if (implementation == null) {
            // 设置默认的缓存实现类
            implementation = PerpetualCache.class;
            if (decorators.isEmpty()) {
                // 添加 LruCache 装饰器
                decorators.add(LruCache.class);
            }
        }
    }

    /**
     * 获取标准的缓存装饰器，一层一层的装饰！
     *
     * @param cache
     * @return
     */
    private Cache setStandardDecorators(Cache cache) {
        try {
            // 创建“元信息”对象
            MetaObject metaCache = SystemMetaObject.forObject(cache);
            if (size != null && metaCache.hasSetter("size")) {
                // 设置 size 属性
                metaCache.setValue("size", size);
            }
            if (clearInterval != null) {
                // clearInterval 不为空，应用 ScheduledCache 装饰器
                cache = new ScheduledCache(cache);
                ((ScheduledCache) cache).setClearInterval(clearInterval);
            }
            if (readWrite) {
                // readWrite 为 true，应用 SerializedCache 装饰器
                cache = new SerializedCache(cache);
            }
            //应用 LoggingCache，SynchronizedCache 装饰器,使原缓存具备打印日志和线程同步的能力
            cache = new LoggingCache(cache);
            cache = new SynchronizedCache(cache);
            if (blocking) {
                // blocking 为 true，应用 BlockingCache 装饰器
                cache = new BlockingCache(cache);
            }
            return cache;
        } catch (Exception e) {
            throw new CacheException("Error building standard cache decorators.  Cause: " + e, e);
        }
    }

    private void setCacheProperties(Cache cache) {
        if (properties != null) {
            // 为缓存实例生成一个“元信息”实例，forObject 方法调用层次比较深， 但最终调用了 MetaClass 的 forClass 方法。
            MetaObject metaCache = SystemMetaObject.forObject(cache);
            for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                String name = (String) entry.getKey();
                String value = (String) entry.getValue();
                if (metaCache.hasSetter(name)) {
                    // 获取 setter 方法的参数类型
                    Class<?> type = metaCache.getSetterType(name);
                    // 根据参数类型对属性值进行转换，并将转换后的值
                    // 通过 setter 方法设置到 Cache 实例中
                    if (String.class == type) {
                        metaCache.setValue(name, value);
                    } else if (int.class == type
                            || Integer.class == type) {
                        /*
                         * 此处及以下分支包含两个步骤:
                         * 1.类型转换 → Integer.valueOf(value)
                         * 2.将转换后的值设置到缓存实例中
                         */
                        metaCache.setValue(name, value);
                        metaCache.setValue(name, Integer.valueOf(value));
                    } else if (long.class == type
                            || Long.class == type) {
                        metaCache.setValue(name, Long.valueOf(value));
                    } else if (short.class == type
                            || Short.class == type) {
                        metaCache.setValue(name, Short.valueOf(value));
                    } else if (byte.class == type
                            || Byte.class == type) {
                        metaCache.setValue(name, Byte.valueOf(value));
                    } else if (float.class == type
                            || Float.class == type) {
                        metaCache.setValue(name, Float.valueOf(value));
                    } else if (boolean.class == type
                            || Boolean.class == type) {
                        metaCache.setValue(name, Boolean.valueOf(value));
                    } else if (double.class == type
                            || Double.class == type) {
                        metaCache.setValue(name, Double.valueOf(value));
                    } else {
                        throw new CacheException("Unsupported property type for cache: '" + name + "' of type " + type);
                    }
                }
            }
        }
        // todo 如果缓存类实现了 InitializingObject 接口，则调用 initialize 方法执行初始化逻辑
        if (InitializingObject.class.isAssignableFrom(cache.getClass())) {
            try {
                ((InitializingObject) cache).initialize();
            } catch (Exception e) {
                throw new CacheException("Failed cache initialization for '"
                        + cache.getId() + "' on '" + cache.getClass().getName() + "'", e);
            }
        }
    }

    private Cache newBaseCacheInstance(Class<? extends Cache> cacheClass, String id) {
        Constructor<? extends Cache> cacheConstructor = getBaseCacheConstructor(cacheClass);
        try {
            return cacheConstructor.newInstance(id);
        } catch (Exception e) {
            throw new CacheException("Could not instantiate cache implementation (" + cacheClass + "). Cause: " + e, e);
        }
    }

    private Constructor<? extends Cache> getBaseCacheConstructor(Class<? extends Cache> cacheClass) {
        try {
            return cacheClass.getConstructor(String.class);
        } catch (Exception e) {
            throw new CacheException("Invalid base cache implementation (" + cacheClass + ").  "
                    + "Base cache implementations must have a constructor that takes a String id as a parameter.  Cause: " + e, e);
        }
    }

    private Cache newCacheDecoratorInstance(Class<? extends Cache> cacheClass, Cache base) {
        Constructor<? extends Cache> cacheConstructor = getCacheDecoratorConstructor(cacheClass);
        try {
            return cacheConstructor.newInstance(base);
        } catch (Exception e) {
            throw new CacheException("Could not instantiate cache decorator (" + cacheClass + "). Cause: " + e, e);
        }
    }

    private Constructor<? extends Cache> getCacheDecoratorConstructor(Class<? extends Cache> cacheClass) {
        try {
            return cacheClass.getConstructor(Cache.class);
        } catch (Exception e) {
            throw new CacheException("Invalid cache decorator (" + cacheClass + ").  "
                    + "Cache decorators must have a constructor that takes a Cache instance as a parameter.  Cause: " + e, e);
        }
    }
}
