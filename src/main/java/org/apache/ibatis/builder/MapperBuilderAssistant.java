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
package org.apache.ibatis.builder;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

import java.util.*;

/**
 * Mapper构建助理
 *
 * @author Clinton Begin
 */
public class MapperBuilderAssistant extends BaseBuilder {

    private String currentNamespace;
    private final String resource;
    private Cache currentCache;
    /**
     * 缓存依赖处理是否完成
     * issue #676
     */
    private boolean unresolvedCacheRef;

    public MapperBuilderAssistant(Configuration configuration, String resource) {
        super(configuration);
        ErrorContext.instance().resource(resource);
        this.resource = resource;
    }

    public String getCurrentNamespace() {
        return currentNamespace;
    }

    public void setCurrentNamespace(String currentNamespace) {
        if (currentNamespace == null) {
            throw new BuilderException("The mapper element requires a namespace attribute to be specified.");
        }

        if (this.currentNamespace != null && !this.currentNamespace.equals(currentNamespace)) {
            throw new BuilderException("Wrong namespace. Expected '"
                    + this.currentNamespace + "' but found '" + currentNamespace + "'.");
        }

        this.currentNamespace = currentNamespace;
    }

    /**
     * 确定 ResultMap 的id
     *
     * @param base
     * @param isReference
     * @return
     */
    public String applyCurrentNamespace(String base, boolean isReference) {
        if (base == null) {
            return null;
        }
        if (isReference) {
            // is it qualified with any namespace yet?
            if (base.contains(".")) {
                return base;
            }
        } else {
            // is it qualified with this namespace yet?
            if (base.startsWith(currentNamespace + ".")) {
                return base;
            }
            if (base.contains(".")) {
                throw new BuilderException("Dots are not allowed in element names, please remove it from " + base);
            }
        }
        return currentNamespace + "." + base;
    }


    public Cache useCacheRef(String namespace) {
        if (namespace == null) {
            throw new BuilderException("cache-ref element requires a namespace attribute.");
        }
        try {
            unresolvedCacheRef = true;
            //获取所依赖的命名空间的缓存实例
            Cache cache = configuration.getCache(namespace);
            /*
                若未查找到缓存实例，此处抛出异常。这里存在两种情况导致未查找到 cache实例，分别如下:
                1.使用者在 <cache-ref> 中配置了一个不存在的命名空间， 导致无法找到 cache 实例
                2.使用者所引用的缓存实例还未创建
             */
            if (cache == null) {
                throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.");
            }
            // 设置 cache 为当前使用缓存
            currentCache = cache;
            unresolvedCacheRef = false;
            return cache;
        } catch (IllegalArgumentException e) {
            throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.", e);
        }
    }

    /**
     * 构建缓存对象
     *
     * @param typeClass
     * @param evictionClass
     * @param flushInterval
     * @param size
     * @param readWrite
     * @param blocking
     * @param props
     * @return
     */
    public Cache useNewCache(Class<? extends Cache> typeClass,
                             Class<? extends Cache> evictionClass,
                             Long flushInterval,
                             Integer size,
                             boolean readWrite,
                             boolean blocking,
                             Properties props) {
        // 使用建造模式构建缓存实例
        Cache cache = new CacheBuilder(currentNamespace)
                .implementation(valueOrDefault(typeClass, PerpetualCache.class))
                .addDecorator(valueOrDefault(evictionClass, LruCache.class))
                .clearInterval(flushInterval)
                .size(size)
                .readWrite(readWrite)
                .blocking(blocking)
                .properties(props)
                .build();
        // 添加缓存到 Configuration 对象中
        configuration.addCache(cache);
        // 设置 currentCache ，即当前使用的缓存
        currentCache = cache;
        return cache;
    }

    public ParameterMap addParameterMap(String id, Class<?> parameterClass, List<ParameterMapping> parameterMappings) {
        id = applyCurrentNamespace(id, false);
        ParameterMap parameterMap = new ParameterMap.Builder(configuration, id, parameterClass, parameterMappings).build();
        configuration.addParameterMap(parameterMap);
        return parameterMap;
    }

    public ParameterMapping buildParameterMapping(
            Class<?> parameterType,
            String property,
            Class<?> javaType,
            JdbcType jdbcType,
            String resultMap,
            ParameterMode parameterMode,
            Class<? extends TypeHandler<?>> typeHandler,
            Integer numericScale) {
        resultMap = applyCurrentNamespace(resultMap, true);

        // Class parameterType = parameterMapBuilder.type();
        Class<?> javaTypeClass = resolveParameterJavaType(parameterType, property, javaType, jdbcType);
        TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);

        return new ParameterMapping.Builder(configuration, property, javaTypeClass)
                .jdbcType(jdbcType)
                .resultMapId(resultMap)
                .mode(parameterMode)
                .numericScale(numericScale)
                .typeHandler(typeHandlerInstance)
                .build();
    }

    /**
     * 解析ResultMap并注册到Configuration
     *
     * @param id
     * @param type
     * @param extend
     * @param discriminator
     * @param resultMappings
     * @param autoMapping
     * @return
     */
    public ResultMap addResultMap(
            String id,
            Class<?> type,
            String extend,
            Discriminator discriminator,
            List<ResultMapping> resultMappings,
            Boolean autoMapping) {
        id = applyCurrentNamespace(id, false);
        extend = applyCurrentNamespace(extend, true);

        if (extend != null) {
            if (!configuration.hasResultMap(extend)) {
                throw new IncompleteElementException("Could not find a parent resultmap with id '" + extend + "'");
            }
            ResultMap resultMap = configuration.getResultMap(extend);
            List<ResultMapping> extendedResultMappings = new ArrayList<>(resultMap.getResultMappings());
            // 为拓展 ResultMappings 取出重复项
            extendedResultMappings.removeAll(resultMappings);
            // Remove parent constructor if this resultMap declares a constructor.
            boolean declaresConstructor = false;
            // 检测当前 resultMappings 集合中是否包含 CONSTRUCTOR 标志的元素
            for (ResultMapping resultMapping : resultMappings) {
                if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
                    declaresConstructor = true;
                    break;
                }
            }
            // 如果当前 <resultMap> 节点中包含 <constructor> 子节点，则将拓展 ResultMapping 集合中的包含 CONSTRUCTOR 标志的元素移除
            if (declaresConstructor) {
                extendedResultMappings.removeIf(resultMapping -> resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR));
            }
            // 将扩展 resultMappings 集合合并到当前 resultMappings 集合中
            resultMappings.addAll(extendedResultMappings);
        }
        // 构建 ResultMap
        ResultMap resultMap = new ResultMap.Builder(configuration, id, type, resultMappings, autoMapping)
                .discriminator(discriminator)
                .build();
        //将ResultMap添加到全局ResultMap集合中
        configuration.addResultMap(resultMap);
        return resultMap;
    }

    public Discriminator buildDiscriminator(
            Class<?> resultType,
            String column,
            Class<?> javaType,
            JdbcType jdbcType,
            Class<? extends TypeHandler<?>> typeHandler,
            Map<String, String> discriminatorMap) {
        ResultMapping resultMapping = buildResultMapping(
                resultType,
                null,
                column,
                javaType,
                jdbcType,
                null,
                null,
                null,
                null,
                typeHandler,
                new ArrayList<>(),
                null,
                null,
                false);
        Map<String, String> namespaceDiscriminatorMap = new HashMap<>();
        for (Map.Entry<String, String> e : discriminatorMap.entrySet()) {
            String resultMap = e.getValue();
            resultMap = applyCurrentNamespace(resultMap, true);
            namespaceDiscriminatorMap.put(e.getKey(), resultMap);
        }
        return new Discriminator.Builder(configuration, resultMapping, namespaceDiscriminatorMap).build();
    }

    /**
     * 构建MappedStatement对象并存储到Configuration 的 mappedStatements
     *
     * @param id
     * @param sqlSource
     * @param statementType
     * @param sqlCommandType
     * @param fetchSize
     * @param timeout
     * @param parameterMap
     * @param parameterType
     * @param resultMap
     * @param resultType
     * @param resultSetType
     * @param flushCache
     * @param useCache
     * @param resultOrdered
     * @param keyGenerator
     * @param keyProperty
     * @param keyColumn
     * @param databaseId
     * @param lang
     * @param resultSets
     * @return
     */
    public MappedStatement addMappedStatement(
            String id,
            SqlSource sqlSource,
            StatementType statementType,
            SqlCommandType sqlCommandType,
            Integer fetchSize,
            Integer timeout,
            String parameterMap,
            Class<?> parameterType,
            String resultMap,
            Class<?> resultType,
            ResultSetType resultSetType,
            boolean flushCache,
            boolean useCache,
            boolean resultOrdered,
            KeyGenerator keyGenerator,
            String keyProperty,
            String keyColumn,
            String databaseId,
            LanguageDriver lang,
            String resultSets) {

        if (unresolvedCacheRef) {
            throw new IncompleteElementException("Cache-ref not yet resolved");
        }

        id = applyCurrentNamespace(id, false);
        boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
        // 创建建造器，设置各种属性
        MappedStatement.Builder statementBuilder = new MappedStatement.Builder(configuration, id, sqlSource, sqlCommandType)
                .resource(resource)
                .fetchSize(fetchSize)
                .timeout(timeout)
                .statementType(statementType)
                .keyGenerator(keyGenerator)
                .keyProperty(keyProperty)
                .keyColumn(keyColumn)
                .databaseId(databaseId)
                .lang(lang)
                .resultOrdered(resultOrdered)
                .resultSets(resultSets)
                .resultMaps(getStatementResultMaps(resultMap, resultType, id))
                .resultSetType(resultSetType)
                .flushCacheRequired(valueOrDefault(flushCache, !isSelect))
                .useCache(valueOrDefault(useCache, isSelect))
                .cache(currentCache);
        // 获取或创建 ParameterMap
        ParameterMap statementParameterMap = getStatementParameterMap(parameterMap, parameterType, id);
        if (statementParameterMap != null) {
            statementBuilder.parameterMap(statementParameterMap);
        }
        // 构建 MappedStatement
        MappedStatement statement = statementBuilder.build();
        // 添加 MappedStatement 到 configuration 的 mappedStatements 集合中
        configuration.addMappedStatement(statement);
        return statement;
    }

    private <T> T valueOrDefault(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    private ParameterMap getStatementParameterMap(
            String parameterMapName,
            Class<?> parameterTypeClass,
            String statementId) {
        parameterMapName = applyCurrentNamespace(parameterMapName, true);
        ParameterMap parameterMap = null;
        if (parameterMapName != null) {
            try {
                parameterMap = configuration.getParameterMap(parameterMapName);
            } catch (IllegalArgumentException e) {
                throw new IncompleteElementException("Could not find parameter map " + parameterMapName, e);
            }
        } else if (parameterTypeClass != null) {
            List<ParameterMapping> parameterMappings = new ArrayList<>();
            parameterMap = new ParameterMap.Builder(
                    configuration,
                    statementId + "-Inline",
                    parameterTypeClass,
                    parameterMappings).build();
        }
        return parameterMap;
    }

    private List<ResultMap> getStatementResultMaps(
            String resultMap,
            Class<?> resultType,
            String statementId) {
        resultMap = applyCurrentNamespace(resultMap, true);

        List<ResultMap> resultMaps = new ArrayList<>();
        if (resultMap != null) {
            String[] resultMapNames = resultMap.split(",");
            for (String resultMapName : resultMapNames) {
                try {
                    resultMaps.add(configuration.getResultMap(resultMapName.trim()));
                } catch (IllegalArgumentException e) {
                    throw new IncompleteElementException("Could not find result map " + resultMapName, e);
                }
            }
        } else if (resultType != null) {
            ResultMap inlineResultMap = new ResultMap.Builder(
                    configuration,
                    statementId + "-Inline",
                    resultType,
                    new ArrayList<>(),
                    null).build();
            resultMaps.add(inlineResultMap);
        }
        return resultMaps;
    }

    /**
     * 解析 ResultMapping
     *
     * @param resultType
     * @param property
     * @param column
     * @param javaType
     * @param jdbcType
     * @param nestedSelect
     * @param nestedResultMap
     * @param notNullColumn
     * @param columnPrefix
     * @param typeHandler
     * @param flags
     * @param resultSet
     * @param foreignColumn
     * @param lazy
     * @return
     */
    public ResultMapping buildResultMapping(
            Class<?> resultType,
            String property,
            String column,
            Class<?> javaType,
            JdbcType jdbcType,
            String nestedSelect,
            String nestedResultMap,
            String notNullColumn,
            String columnPrefix,
            Class<? extends TypeHandler<?>> typeHandler,
            List<ResultFlag> flags,
            String resultSet,
            String foreignColumn,
            boolean lazy) {
        // 若 javaType 为空，这里根据 property 的属性进行解析。关于下面方法中的参数，
        // 这里说明一下:
        // - resultType:即 <resultMap type="xxx"/> 中的 type 属性
        // - property:即 <result property="xxx"/> 中的 property 属性
        Class<?> javaTypeClass = resolveResultJavaType(resultType, property, javaType);
        // 解析 TypeHandler
        TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);
        // 解析 column = {property1=column1, property2=column2} 的情况，
        // 这里会将 column 拆分成多个 ResultMapping
        List<ResultMapping> composites = parseCompositeColumnName(column);
        // 通过建造模式构建 ResultMapping
        return new ResultMapping.Builder(configuration, property, column, javaTypeClass)
                .jdbcType(jdbcType)
                .nestedQueryId(applyCurrentNamespace(nestedSelect, true))
                .nestedResultMapId(applyCurrentNamespace(nestedResultMap, true))
                .resultSet(resultSet)
                .typeHandler(typeHandlerInstance)
                .flags(flags == null ? new ArrayList<>() : flags)
                .composites(composites)
                .notNullColumns(parseMultipleColumnNames(notNullColumn))
                .columnPrefix(columnPrefix)
                .foreignColumn(foreignColumn)
                .lazy(lazy)
                .build();
    }

    private Set<String> parseMultipleColumnNames(String columnName) {
        Set<String> columns = new HashSet<>();
        if (columnName != null) {
            if (columnName.indexOf(',') > -1) {
                StringTokenizer parser = new StringTokenizer(columnName, "{}, ", false);
                while (parser.hasMoreTokens()) {
                    String column = parser.nextToken();
                    columns.add(column);
                }
            } else {
                columns.add(columnName);
            }
        }
        return columns;
    }

    private List<ResultMapping> parseCompositeColumnName(String columnName) {
        List<ResultMapping> composites = new ArrayList<>();
        if (columnName != null && (columnName.indexOf('=') > -1 || columnName.indexOf(',') > -1)) {
            StringTokenizer parser = new StringTokenizer(columnName, "{}=, ", false);
            while (parser.hasMoreTokens()) {
                String property = parser.nextToken();
                String column = parser.nextToken();
                ResultMapping complexResultMapping = new ResultMapping.Builder(
                        configuration, property, column, configuration.getTypeHandlerRegistry().getUnknownTypeHandler()).build();
                composites.add(complexResultMapping);
            }
        }
        return composites;
    }

    private Class<?> resolveResultJavaType(Class<?> resultType, String property, Class<?> javaType) {
        if (javaType == null && property != null) {
            try {
                MetaClass metaResultType = MetaClass.forClass(resultType, configuration.getReflectorFactory());
                javaType = metaResultType.getSetterType(property);
            } catch (Exception e) {
                //ignore, following null check statement will deal with the situation
            }
        }
        if (javaType == null) {
            javaType = Object.class;
        }
        return javaType;
    }

    private Class<?> resolveParameterJavaType(Class<?> resultType, String property, Class<?> javaType, JdbcType jdbcType) {
        if (javaType == null) {
            if (JdbcType.CURSOR.equals(jdbcType)) {
                javaType = java.sql.ResultSet.class;
            } else if (Map.class.isAssignableFrom(resultType)) {
                javaType = Object.class;
            } else {
                MetaClass metaResultType = MetaClass.forClass(resultType, configuration.getReflectorFactory());
                javaType = metaResultType.getGetterType(property);
            }
        }
        if (javaType == null) {
            javaType = Object.class;
        }
        return javaType;
    }

    /**
     * Backward compatibility signature.
     */
    public ResultMapping buildResultMapping(Class<?> resultType, String property, String column, Class<?> javaType,
                                            JdbcType jdbcType, String nestedSelect, String nestedResultMap, String notNullColumn, String columnPrefix,
                                            Class<? extends TypeHandler<?>> typeHandler, List<ResultFlag> flags) {
        return buildResultMapping(
                resultType, property, column, javaType, jdbcType, nestedSelect,
                nestedResultMap, notNullColumn, columnPrefix, typeHandler, flags, null, null, configuration.isLazyLoadingEnabled());
    }

    /**
     * @deprecated Use {@link Configuration#getLanguageDriver(Class)}
     */
    @Deprecated
    public LanguageDriver getLanguageDriver(Class<? extends LanguageDriver> langClass) {
        return configuration.getLanguageDriver(langClass);
    }

    /**
     * Backward compatibility signature.
     */
    public MappedStatement addMappedStatement(String id, SqlSource sqlSource, StatementType statementType,
                                              SqlCommandType sqlCommandType, Integer fetchSize, Integer timeout, String parameterMap, Class<?> parameterType,
                                              String resultMap, Class<?> resultType, ResultSetType resultSetType, boolean flushCache, boolean useCache,
                                              boolean resultOrdered, KeyGenerator keyGenerator, String keyProperty, String keyColumn, String databaseId,
                                              LanguageDriver lang) {
        return addMappedStatement(
                id, sqlSource, statementType, sqlCommandType, fetchSize, timeout,
                parameterMap, parameterType, resultMap, resultType, resultSetType,
                flushCache, useCache, resultOrdered, keyGenerator, keyProperty,
                keyColumn, databaseId, lang, null);
    }

}
