/**
 *    Copyright 2009-2020 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.executor.resultset;

import org.apache.ibatis.annotations.AutomapConstructor;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.cursor.defaults.DefaultCursor;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.*;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.lang.reflect.Constructor;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Iwao AVE!
 * @author Kazuki Shimizu
 *  默认的结果集处理器
 *  <p>
 *     别名说明：
 *     <ol>
 *         <li>Mapper.xml的resultMap标签信息封装类对象 简称 resultMap标签对象</li>
 *         <li>根据resultMap对象与结果集构建出来的java类型对象，简称为 结果对象</li>
 *     </ol>
 *
 * </p>
 *
 */
public class DefaultResultSetHandler implements ResultSetHandler {

  /**
   * 标记着待定的对象
   */
  private static final Object DEFERRED = new Object();

  /**
   * 执行器
   */
  private final Executor executor;
  /**
   * mybatis全局配置信息
   */
  private final Configuration configuration;
  /**
   *  Mapper.xml文件的select,delete,update,insert这些DML标签的封装类
   */
  private final MappedStatement mappedStatement;
  /**
   * Mybatis的分页对象
   */
  private final RowBounds rowBounds;
  /**
   * 参数处理器
   */
  private final ParameterHandler parameterHandler;
  /**
   * 结果处理器
   */
  private final ResultHandler<?> resultHandler;
  /**
   * 参数映射与可执行SQL封装类对象
   */
  private final BoundSql boundSql;
  /**
   * 类型处理器注册器对象
   */
  private final TypeHandlerRegistry typeHandlerRegistry;
  /**
   * 对象工厂，从mybatis全局配置信息中获取
   */
  private final ObjectFactory objectFactory;
  /**
   * 反射工厂,从mybatis全局配置信息中获取
   */
  private final ReflectorFactory reflectorFactory;

  // nested resultmaps
  /**
   * key=缓存Key,value=嵌套的结果对象
   */
  private final Map<CacheKey, Object> nestedResultObjects = new HashMap<>();
  private final Map<String, Object> ancestorObjects = new HashMap<>();
  /**
   * 上一次嵌套的resultMap对象所构建出来的对象
   */
  private Object previousRowValue;

  // multiple resultsets
  private final Map<String, ResultMapping> nextResultMaps = new HashMap<>();
  /**
   * PendingRelation是DefaultResultSetHandler的内部静态类，记录了当前结果对象对应的MetaObject对象以及parentMapping对象
   * 该对象就为CacheKey对象跟全部的PendingRelation对象的映射
   */
  private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<>();

  // Cached Automappings
  /**
   * 自动映射关系缓存Map
   */
  private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache = new HashMap<>();

  // temporary marking flag that indicate using constructor mapping (use field to reduce memory usage)
  // 表明使用构造函数映射关系的临时标志标记（使用成员变量来减少内存开销)
  /**
   * 当前结果对象是否使用了非无参构造函数进行构建的标记
   */
  private boolean useConstructorMappings;

  /**
   * 待定关系
   */
  private static class PendingRelation {
    /**
     * 元对象
     */
    public MetaObject metaObject;
    /**
     * 结果映射
     */
    public ResultMapping propertyMapping;
  }

  private static class UnMappedColumnAutoMapping {
    /**
     * 字段名
     */
    private final String column;
    /**
     * 属性名
     */
    private final String property;
    /**
     * TypeHandler 处理器
     */
    private final TypeHandler<?> typeHandler;
    /**
     * 是否为基本属性
     */
    private final boolean primitive;

    /**
     *
     * @param column 字段名
     * @param property 属性名
     * @param typeHandler TypeHandler 处理器
     * @param primitive 是否为基本属性
     */
    public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler, boolean primitive) {
      this.column = column;
      this.property = property;
      this.typeHandler = typeHandler;
      this.primitive = primitive;
    }
  }

  /**
   *
   * @param executor 执行器
   * @param mappedStatement Mapper.xml文件的select,delete,update,insert这些DML标签的封装类
   * @param parameterHandler 参数处理器
   * @param resultHandler 结果处理器
   * @param boundSql 参数映射与可执行SQL封装类对象
   * @param rowBounds Mybatis的分页对象
   */
  public DefaultResultSetHandler(Executor executor, MappedStatement mappedStatement, ParameterHandler parameterHandler, ResultHandler<?> resultHandler, BoundSql boundSql,
                                 RowBounds rowBounds) {
    this.executor = executor;
    this.configuration = mappedStatement.getConfiguration();
    this.mappedStatement = mappedStatement;
    this.rowBounds = rowBounds;
    this.parameterHandler = parameterHandler;
    this.boundSql = boundSql;
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.objectFactory = configuration.getObjectFactory();
    this.reflectorFactory = configuration.getReflectorFactory();
    this.resultHandler = resultHandler;
  }

  //
  // HANDLE OUTPUT PARAMETER
  //

  @Override
  public void handleOutputParameters(CallableStatement cs) throws SQLException {
    //获取参数对象
    final Object parameterObject = parameterHandler.getParameterObject();
    //构建参数元对象
    final MetaObject metaParam = configuration.newMetaObject(parameterObject);
    //获取参数映射集合
    final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    //遍历参数映射集合
    for (int i = 0; i < parameterMappings.size(); i++) {
      //获取参数映射
      final ParameterMapping parameterMapping = parameterMappings.get(i);
      //如果参数映射的模式为输出参数 或者 参数映射的模式为输入/输出参数
      if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
        //如果参数映射配置java类型是ResultSet
        if (ResultSet.class.equals(parameterMapping.getJavaType())) {
          //处理结果游标的输出参数，将结果对象集合赋值到参数对象对应的属性中
          handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1), parameterMapping, metaParam);
        } else {
          //获取类型处理器
          final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
          //通过typeHandler获取指定列位置的结果对象，然后赋值到参数对象对应的属性中
          metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
        }
      }
    }
  }

  /**
   * 处理结果游标的输出参数，将结果对象集合赋值到参数对象对应的属性中
   * @param rs 结果集
   * @param parameterMapping 对应于paramterMap的paramter标签参数映射封装类对象
   * @param metaParam 参数元对象
   */
  private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam) throws SQLException {
    //如果结果集为null
    if (rs == null) {
      return;
    }
    try {
      //获取参数映射配置的resultMap标签Id
      final String resultMapId = parameterMapping.getResultMapId();
      //获取resultMapId对应的ResultMap对象
      final ResultMap resultMap = configuration.getResultMap(resultMapId);
      //将rs进行包装
      final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
      //如果结果处理器为null
      if (this.resultHandler == null) {
        //默认ResultHandler，通过ObjectFactory去构建list来接收结果
        //构建一个默认结果处理器，里面就是通过objectFactory构建list对象,本质是一个list.
        final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
        //处理结果集
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
        //将结果对象集合赋值到参数对象对应的属性中
        metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
      } else {
        //处理结果集
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
      }
    } finally {
      // issue #228 (close resultsets)关闭结果集,捕捉SQLException并不作任何处理
      closeResultSet(rs);
    }
  }

  //
  // HANDLE RESULT SETS
  //   * 处理 {@code stmt} 中的所有结果集，返回结果对象集合
  //   * @param stmt SQL容器
  //   */
  @Override
  public List<Object> handleResultSets(Statement stmt) throws SQLException {
    //设置日志的上下文
    ErrorContext.instance().activity("handling results").object(mappedStatement.getId());
    //初始化一个集合对象，用于保存多个结果对象
    final List<Object> multipleResults = new ArrayList<>();
    //初始化结果集数量为0
    int resultSetCount = 0;
    //获取第一个结果集对象
    //获取ResultMap标签对象集合
    ResultSetWrapper rsw = getFirstResultSet(stmt);

    //声明一个ResultMap对参数进行封装
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();
    //获取集合元素数量
    int resultMapCount = resultMaps.size();
    //验证ResultMap标签对象数量,如果rsw不为null且ResultMapCount小于1，就会抛出异常
    validateResultMapsCount(rsw, resultMapCount);
    //如果结果集包装类对象不为null 且 配置的ResultMap标签对象数量大于结果集数量
    while (rsw != null && resultMapCount > resultSetCount) {
      //从集合中获取ResultMap标签对象
      ResultMap resultMap = resultMaps.get(resultSetCount);
      /**
       * 构建出来的结果对象,如果父级结果属性映射不为null，会将结果对象赋值到父级结果属性对应的结果对象中，
       * 否则将结果对象加入到reusltHandler中。最后从reusltHandler中取的最终的结果对象加入到多个结果
       * 对象集合中
       */
      handleResultSet(rsw, resultMap, multipleResults, null);
      //获取下一个结果集对象
      rsw = getNextResultSet(stmt);
      //清空所有的嵌套结果对象
      cleanUpAfterHandlingResultSet();
      //每处理完一次，结果集数量+1
      resultSetCount++;
    }

    //获取配置的结果集名
    String[] resultSets = mappedStatement.getResultSets();
    //如果结果集不为null
    if (resultSets != null) {
      //如果结果包装对象不为null 而且 结果集数量小于配置的结果集名数量
      while (rsw != null && resultSetCount < resultSets.length) {
        //获取父级结果属性映射对象
        ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
        //如果父级结果属性映射对象不为null
        if (parentMapping != null) {
          //获取嵌套的resultMap标签对象Id
          String nestedResultMapId = parentMapping.getNestedResultMapId();
          //获取nestedResultMapId对应的ResultMap标签对象
          ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
          /**
           * 构建出来的结果对象,如果父级结果属性映射不为null，会将结果对象赋值到父级结果属性对应的结果对象中，
           *  否则将结果对象加入到reusltHandler中。最后从reusltHandler中取的最终的结果对象加入到多个结果
           *  对象集合中
           */
          handleResultSet(rsw, resultMap, null, parentMapping);
        }
        //获取下一个结果集，并封装到包装类中
        rsw = getNextResultSet(stmt);
        //清空所有的嵌套结果对象
        cleanUpAfterHandlingResultSet();
        //每处理完一次，结果集数量+1
        resultSetCount++;
      }
    }
//如果multipleResults只有一个元素，只会返回该元素对象；否则返回multipleResult
    return collapseSingleResultList(multipleResults);
  }

  @Override
  public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());
    //获取第一个结果集对象，并包装起来
    ResultSetWrapper rsw = getFirstResultSet(stmt);
    //获取ResultMap标签对象集合
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();
    //获取集合的元素数量
    int resultMapCount = resultMaps.size();
    //验证ResultMap标签对象数量,如果rsw不为null且ResultMapCount小于1，就会抛出异常
    validateResultMapsCount(rsw, resultMapCount);
    //检查集合是否为1
    if (resultMapCount != 1) {
      //游标结果不能映射到多个ResultMap标签对象
      throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
    }
    //获取唯一的一个ResultMap标签对象
    ResultMap resultMap = resultMaps.get(0);
    //返回默认的mybatis游标实例
    return new DefaultCursor<>(this, resultMap, rsw, rowBounds);
  }

  private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
    //获取结果集
    ResultSet rs = stmt.getResultSet();
    //如果结果集为null
    while (rs == null) {
      // move forward to get the first resultset in case the driver
      // doesn't return the resultset as the first result (HSQLDB 2.1)
      //前进得到第一个结果集以防驱动不返回结果集的第一个结果
      /**
       * getMoreResults:移动到Statement对象的下一个结果,如果下一个结果是ResultSet对象，返回true,
       * 如果是一个更新记录数或者已经没有更多的结果时，返回false
       */
      if (stmt.getMoreResults()) {
        //获取结果集
        rs = stmt.getResultSet();
      } else {
        //如果没有结果集
        //getUpdateCount:更新记录数，没有时为-1
        if (stmt.getUpdateCount() == -1) {
          /**
           * 当((stmt.getMoreResults() == false) && (stmt.getUpdateCount() == -1))
           * 就表示已经没有更多结果
           */
          // no more results. Must be no resultset
          break;
        }
      }
    }
    //如果结果集不为null，就包装起来返回出去；否则，返回null
    return rs != null ? new ResultSetWrapper(rs, configuration) : null;
  }

  /**
   * 获取下一个结果集
   * @param stmt SQL脚本容器
   * @return 结果集包装类对象
   */
  private ResultSetWrapper getNextResultSet(Statement stmt) {
    // Making this method tolerant of bad JDBC drivers
    //使用这个方法能增强对JDBC驱动的容错率
    try {
      //检索这个数据库是否支持从一个单一调用方法执行获得多个结果集对象
      if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
        // Crazy Standard JDBC way of determining if there are more results
        //疯狂的标准JDBC确定是否有更多结果的方法
        //!stmt.getMoreResults() && stmt.getUpdateCount() == -1：表示已经没有更多结果
        //如果有更多结果
        if (!(!stmt.getMoreResults() && stmt.getUpdateCount() == -1)) {
          //获取结果集
          ResultSet rs = stmt.getResultSet();
          //如果结果集对象不为null
          if (rs == null) {
            //递归尝试重新获取下个一个结果集
            return getNextResultSet(stmt);
          } else {
            //将结果集对象包装起来返回
            return new ResultSetWrapper(rs, configuration);
          }
        }
      }
    } catch (Exception e) {
      // Intentionally ignored.
    }
    return null;
  }
  /**
   * 关闭结果集,捕捉SQLException并不作任何处理
   * @param rs 结果集
   */
  private void closeResultSet(ResultSet rs) {
    try {
      //如果结果集不为null
      if (rs != null) {
        //关闭
        rs.close();
      }
    } catch (SQLException e) {
      // ignore 捕捉SQLException并不作任何处理
    }
  }

  /**
   * 清空所有的嵌套结果对象
   */
  private void cleanUpAfterHandlingResultSet() {
    nestedResultObjects.clear();
  }

  /**
   * 验证ResultMap标签对象数量,如果rsw不为null且ResultMapCount小于1，就会抛出异常
   * @param rsw 结果集包装类对象
   * @param resultMapCount ResultMap标签对象数量
   */
  private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
    if (rsw != null && resultMapCount < 1) {
      throw new ExecutorException("A query was run and no Result Maps were found for the Mapped Statement '" + mappedStatement.getId()
          + "'.  It's likely that neither a Result Type nor a Result Map was specified.");
    }
  }

  /**
   * 构建出来的结果对象,如果父级结果属性映射不为null，会将结果对象赋值到父级结果属性对应的结果对象中，
   *  否则将结果对象加入到reusltHandler中。最后从reusltHandler中取的最终的结果对象加入到多个结果
   *  对象集合中
   * @param rsw 结果集包装对象
   * @param resultMap resultMap标签对象
   * @param multipleResults 多个结果对象集合
   * @param parentMapping 父级结果属性映射
   * @throws SQLException
   */
  private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults, ResultMapping parentMapping) throws SQLException {
    try {
      //如果 父级结果属性映射不为null
      if (parentMapping != null) {
        /**
         * 构建出来的结果对象,如果父级结果属性映射不为null，会将结果对象赋值到父级结果属性对应的结果对象中，
         * 否则将结果对象加入到reusltHandler中
         */
        //这里因为paranetMapping已经确定不为null了，所以resultHandler传入null也不会有问题。
        handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
      } else {
        //如果结果处理器对象为null
        if (resultHandler == null) {
          //创建一个默认结果处理类对象
          DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
          /**
           * 构建出来的结果对象,如果父级结果属性映射不为null，会将结果对象赋值到父级结果属性对应的结果对象中，
           *  否则将结果对象加入到reusltHandler中
           */
          handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
          //将结果对象加入到多个结果对象集合中
          multipleResults.add(defaultResultHandler.getResultList());
        } else {
          /**
           * 构建出来的结果对象,如果父级结果属性映射不为null，会将结果对象赋值到父级结果属性对应的结果对象中，
           *  否则将结果对象加入到reusltHandler中
           */
          handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
        }
      }
    } finally {
      // issue #228 (close resultsets)
      //关闭结果集,捕捉SQLException并不作任何处理
      closeResultSet(rsw.getResultSet());
    }
  }

  @SuppressWarnings("unchecked")
  private List<Object> collapseSingleResultList(List<Object> multipleResults) {
    return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults;
  }

  /**
   * 构建出来的结果对象,如果父级结果属性映射不为null，会将结果对象赋值到父级结果属性对应的结果对象中，
   * 否则将结果对象加入到reusltHandler中
   * @param rsw 结果集包装类对象
   * @param resultMap resultMap标签信息封装类对象
   * @param resultHandler 一般为 {@link DefaultResultHandler} 实例
   * @param rowBounds mybatis的分页
   * @param parentMapping 父级结果属性映射
   */
  public void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    //如果存在嵌套的resultMap
    if (resultMap.hasNestedResultMaps()) {
      //确保没有超过分页行范围
      ensureNoRowBounds();
      //检查是否可以使用resultHandler
      checkResultHandler();
      //为嵌套的ResultMap标签对象，将构建出来的结果对象加入到reusltHandler中
      handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
    } else {
      // 根据结果集包装类对象和简单的ResultMap标签对象，构建成结果对象并加入到resultHandler中
      handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
    }
  }

  /**
   * 确保没有超过分页行范围
   * <p>
   *     只有当isSafeRowsEnable(允许在嵌套语句中使用行分界（RowBounds），默认为false，不允许)为ture的时候，才会检查 {@link #rowBounds}的行范围是否超过最大值和最小值，超过时会抛出异常。
   * </p>
   */
  private void ensureNoRowBounds() {
    //isSafeRowsEnable:允许在嵌套语句中使用行分界（RowBounds），默认为false，不允许
    //当允许时，会判断rowBound的范围是否超过最大值和最小值，超过时会抛出异常。
    if (configuration.isSafeRowBoundsEnabled() && rowBounds != null && (rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT || rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
      throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
        + "Use safeRowBoundsEnabled=false setting to bypass this check.");
    }
  }

  protected void checkResultHandler() {
    if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement.isResultOrdered()) {
      throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
          + "Use safeResultHandlerEnabled=false setting to bypass this check "
          + "or ensure your statement returns ordered data and set resultOrdered=true on it.");
    }
  }

  /**
   * 根据结果集包装类对象和简单的ResultMap标签对象，构建成结果对象
   * @param rsw 结果集包装类对象
   * @param resultMap ResultMap标签对象
   * @param resultHandler 结果处理器
   * @param rowBounds Mybatis的分页对象
   * @param parentMapping 父级属性结果映射
   * @throws SQLException
   */
  private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
      throws SQLException {
    //新建一个默认的结果上下文
    DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    //获取结果集
    ResultSet resultSet = rsw.getResultSet();
    ////跳过rowBounds.offset记录数
    skipRows(resultSet, rowBounds);
    //如果还有结果需要处理 而且 结果集还没有关闭 而且 结果集还有数据
    while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
      //逐层解析resultMap鉴别器，取得最终的ResultMap标签对象
      ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
      //根据rsw 和resultMap 构建出 {@link ResultMap#getType()} 类型的对象
      Object rowValue = getRowValue(rsw, discriminatedResultMap, null);
      /**
       * 保存对象,如果parentMaping 不为null,就将结果对象添加到parentMapping
       * 的metaObject中;否则，调用结果处理器，将结果对象添加到结果处理器中
       */
      storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
    }
  }

  /**
   * 保存对象,如果 {@code parentMaping} 不为null,就将结果对象添加到 {@code parentMapping}
   * 的metaObject中;否则，调用结果处理器，将结果对象添加到结果处理器中
   * @param resultHandler 结果处理类对象
   * @param resultContext 结果上下文
   * @param rowValue resultMap对象
   * @param parentMapping 结果映射
   * @param rs 结果集
   */
  private void storeObject(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue, ResultMapping parentMapping, ResultSet rs) throws SQLException {
    //如果上一层的ResultMapping对象不为null
    if (parentMapping != null) {
      //将嵌套对象记录到外层元对象相应的属性中
      linkToParents(rs, parentMapping, rowValue);
    } else {
      //调用结果处理器，将结果对象添加到结果处理器中
      callResultHandler(resultHandler, resultContext, rowValue);
    }
  }

  /**
   * 调用结果处理器，将结果对象添加到结果处理器中
   * @param resultHandler 结果处理器，
   * @param resultContext 结果上下文
   * @param rowValue 结果对象
   */
  @SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object>*/)
  private void callResultHandler(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue) {
    //设置下一个结果对象
    resultContext.nextResultObject(rowValue);
    /**
     * 如果是DefaultResultHandler，就会将结果对象添加到内部的List对象中
     * 如果是DefaultMapResultHandler，会将结果对象添加到内部的Map对象中
     */
    ((ResultHandler<Object>) resultHandler).handleResult(resultContext);
  }

  /**
   * 是否还有结果需要处理
   * @param context 结果上下文
   * @param rowBounds mybatis的分页对象
   * @return 当 {@code context} 没有停止，且 {@code context} 的resultCount小于 {@code rowBounds} 的 limit是，为true，否则为false
   */
  private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) {
    return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
  }

  /**
   * 跳过rowBounds.offset记录数
   * @param rs  结果集
   * @param rowBounds Mybatis的分页对象
   * @throws SQLException
   */
  private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
    //ResultSet.TYPE_FORWORD_ONLY 结果集的游标只能向下滚动
    if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
      if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
        //absolute:将指针移动到此 ResultSet 对象的给定行编号。
        rs.absolute(rowBounds.getOffset());
      }
    } else {
      //通过ResultSet的next()改变rs的游标位置直到达到rowBounds设置的offset
      for (int i = 0; i < rowBounds.getOffset(); i++) {
        if (!rs.next()) {
          break;
        }
      }
    }
  }

  /**
   * 根据 {@code rsw} 和 {@code resultMap} 构建出 {@link ResultMap#getType()} 类型的对象
   * @param rsw 结果集包装类对象
   * @param resultMap Mapper.xml的resultMap标签信息封装类对象
   * @param columnPrefix 列名前缀
   * @return {@link ResultMap#getType()} 类型的对象
   */
  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
    //用来表示需要懒加载的属性集，本质是一个HashMap
    final ResultLoaderMap lazyLoader = new ResultLoaderMap();
    //根据 {@code rsw} 和 {@code resultMap} 的配置构建结果对象
    Object rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
    //结果对象不为null但没有对应结果类型的TypeHandler
    if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      //构建结果元对象
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      //当前结果对象是否使用了非无参构造函数进行构建的标记
      boolean foundValues = this.useConstructorMappings;
      //是否可以应用自动映射
      if (shouldApplyAutomaticMappings(resultMap, false)) {
        //对未能被映射列名映射关系集合进行尝试赋值，将结果集对应数据赋值到结果对象对应的属性中
        foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
      }
      // applyPropertyMappings：根据结果集和属性映射构建对应的目标对象，并赋值到结果对象中
      // 这时候的foundValue表示只有一个根据属性映射和结果集成功构建出对应的目标对象，foundValue就为true
      foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
      foundValues = lazyLoader.size() > 0 || foundValues;
      /**
       * isReturnInstanceForEmptyRow：
       * 当返回行的所有列都是空时，MyBatis默认返回 null。
       * 当开启这个设置时,MyBatis会返回一个空实例.请注意，它也适用于嵌套的结果集(如集合或关联).
       * (新增于 3.4.2)
       */
      rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
    }
    return rowValue;
  }

  //
  // GET VALUE FROM ROW FOR NESTED RESULT MAP
  //

  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix, Object partialObject) throws SQLException {
    final String resultMapId = resultMap.getId();
    Object rowValue = partialObject;
    if (rowValue != null) {
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      putAncestor(rowValue, resultMapId);
      applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
      ancestorObjects.remove(resultMapId);
    } else {
      final ResultLoaderMap lazyLoader = new ResultLoaderMap();
      rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
      if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
        final MetaObject metaObject = configuration.newMetaObject(rowValue);
        boolean foundValues = this.useConstructorMappings;
        if (shouldApplyAutomaticMappings(resultMap, true)) {
          foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
        }
        foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
        putAncestor(rowValue, resultMapId);
        foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true) || foundValues;
        ancestorObjects.remove(resultMapId);
        foundValues = lazyLoader.size() > 0 || foundValues;
        rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
      }
      if (combinedKey != CacheKey.NULL_CACHE_KEY) {
        nestedResultObjects.put(combinedKey, rowValue);
      }
    }
    return rowValue;
  }

  private void putAncestor(Object resultObject, String resultMapId) {
    ancestorObjects.put(resultMapId, resultObject);
  }

  /**
   * 是否可以应用自动映射
   * @param resultMap Mapper.xml的resultMap标签信息封装类对象
   * @param isNested 是否是嵌套的
   * @return 可以应用自动映射，返回true；否则返回false
   */
  private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
    //如果配置了自动映射标记
    if (resultMap.getAutoMapping() != null) {
      //自动映射
      return resultMap.getAutoMapping();
    } else {
      //如果是嵌套
      if (isNested) {
        //AutoMappingBehavior.FULL表示： 自动映射任意复杂的结果集（无论是否嵌套）。
        return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
      } else {
        //AutoMappingBehavior.NODE：取消自动映射
        //AutoMappingBehavior.PARTIAL：自动映射没有定义嵌套结果集映射的结果集
        return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();
      }
    }
  }


  //
  // PROPERTY MAPPINGS
  //

  /**
   * 根据结果集和属性映射构建对应的目标对象，并赋值到结果对象中
   * @param rsw 结果集包装类对象
   * @param resultMap Mapper.xml的resultMap标签信息封装类对象
   * @param metaObject 结果元对象
   * @param lazyLoader 用来表示需要懒加载的属性集，本质是一个HashMap
   * @param columnPrefix 列名前缀
   * @return 成功根据结果集和属性映射构建对应的目标对象的标记，哪怕只有一个成功，都会为true
   */
  private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, ResultLoaderMap lazyLoader, String columnPrefix)
    throws SQLException {
    //获取映射列名集合
    final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
    //成功根据结果集和属性映射构建对应的目标对象的标记，哪怕只有一个成功，都会为true
    boolean foundValues = false;
    //获取属性映射关系集合，该集合不包括标记为构造函数的属性映射关系
    final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
    //遍历属性映射关系
    for (ResultMapping propertyMapping : propertyMappings) {
      //拼接前缀与列名
      String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      //如果配置了嵌套的resultMapId
      if (propertyMapping.getNestedResultMapId() != null) {
        // the user added a column attribute to a nested result map, ignore it
        //用户添加一个列属性是一个嵌套映射结果,忽略它;
        column = null;
      }
      /**
       *  如果属性映射存在组合ResultMaping列表
       *  或者 column不为null且映射列名集合中包含column
       *  或者 属性映射配置了用于加载复杂类型的结果集
       */
      if (propertyMapping.isCompositeResult()
        || (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH)))
        || propertyMapping.getResultSet() != null) {
        //根据结果集和属性映射构建对应的目标对象
        Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader, columnPrefix);
        // issue #541 make property optional
        //获取属性映射的属性名
        final String property = propertyMapping.getProperty();
        //如果没有配置属性名
        if (property == null) {
          //跳过该属性映射
          continue;
          //如果目标对象是一个标记为待定的对象
        } else if (value == DEFERRED) {
          //成功根据结果集和属性映射构建对应的目标对象的标记设置为true
          foundValues = true;
          continue;
        }
        //如果目标对象不为null
        if (value != null) {
          //成功根据结果集和属性映射构建对应的目标对象的标记设置为true
          foundValues = true;
        }
        /**
         * isCallSetterOnNulls:
         * 指定当结果集中值为 null 的时候是否调用映射对象的 setter（map 对象时为 put）方法，
         * 这在依赖于 Map.keySet() 或 null 值初始化的时候比较有用。
         * 注意基本类型（int、boolean 等）是不能设置成 null 的。
         */
        //如果对象不为null,或者指定当结果集中值为null的时候允许调用映射对象的setter(map对象时为 put)方法,且不是基本类型
        if (value != null || (configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property).isPrimitive())) {
          // gcode issue #377, call setter on nulls (value is not 'found')
          //将value赋值到结果对象中的mapping.property属性中
          metaObject.setValue(property, value);
        }
      }
    }
    return foundValues;
  }

  /**
   * 根据结果集和属性映射对应的目标对象
   * @param rs 结果集
   * @param metaResultObject 结果元对象
   * @param propertyMapping 属性映射
   * @param lazyLoader 用来表示需要懒加载的属性集，本质是一个HashMap
   * @param columnPrefix 列名前缀
   * @return 根据结果集和属性映射构建对应的目标对象
   */
  private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
    throws SQLException {
    //如果属性映射配置了嵌套select标签Id
    if (propertyMapping.getNestedQueryId() != null) {
      //构建带有嵌套查询属性的值对象
      return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
      //如果加载复杂类型的结果集
    } else if (propertyMapping.getResultSet() != null) {
      // 添加 {propertyMapping待定关系到待定关系映射集合 {@link #pendingRelations}
      addPendingChildRelation(rs, metaResultObject, propertyMapping);   // TODO is that OK?
      //返回标记着待定的对象
      return DEFERRED;
    } else {
      //属性映射的默认情况处理
      //获取属性映射的TypeHandler
      final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
      //获取属性映射的列名，并拼接列名前缀
      final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      //从 ResultSet 中取出columnName对应数据，然后转换为 java 对象
      return typeHandler.getResult(rs, column);
    }
  }

  /**
   * 获取未能被映射列名映射关系集合
   * @param rsw 结果集包装类对象
   * @param resultMap Mapper.xml的resultMap标签信息封装类对象
   * @param metaObject 结果元对象
   * @param columnPrefix 列名
   * @return 未能被映射列名映射关系集合
   * @throws SQLException
   */
  private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
    //构建autoMappingCache的Key
    final String mapKey = resultMap.getId() + ":" + columnPrefix;
    //获取mapKey的自动映射集合
    List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);
    //如果自动映射集合为null
    if (autoMapping == null) {
      //初始化自动映射集合
      autoMapping = new ArrayList<>();
      //获取未被映射的列名集合
      final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
      //遍历未被映射的列名集合
      for (String columnName : unmappedColumnNames) {
        //将列名当作属性名
        String propertyName = columnName;
        //如果列名前缀不为null且不是空字符串
        if (columnPrefix != null && !columnPrefix.isEmpty()) {
          // When columnPrefix is specified,
          // ignore columns without the prefix.
          //当列名前缀被指定了，就忽略没有指定前缀的列
          //列名是以columnPrefix开头
          if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
            //去掉columnPrefix赋值给propertyName
            propertyName = columnName.substring(columnPrefix.length());
          } else {
            //忽略没有指定前缀的列
            continue;
          }
        }
        /**
         * isMapUnderscoreToCamelCase：是否开启自动驼峰命名规则（camel case）映射，
         * 即从经典数据库列名 A_COLUMN 到经典 Java 属性名 aColumn 的类似映射
         */
        //查看结果对象是否存在propertyName属性,存在便会返回该属性名
        final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());
        //如果属性不为null且结果对象有这个属性的setter方法
        if (property != null && metaObject.hasSetter(property)) {
          //如果配置的映射属性名中有这个属性
          if (resultMap.getMappedProperties().contains(property)) {
            //跳过
            continue;
          }
          //获取结果对象的property的setter方法的参数类型
          final Class<?> propertyType = metaObject.getSetterType(property);
          //如果存在propertyType和columnName的jdbcType的TypeHandler
          if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
            //获取propertyType和columnName的TypeHandler
            final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
            //构建UnMappedColumnAutoMapping对象，并添加到autoMapping中
            autoMapping.add(new UnMappedColumnAutoMapping(columnName, property, typeHandler, propertyType.isPrimitive()));
          } else {
            //不存在propertyType和columnName的jdbcType的TypeHandler时
            /**
             * AutoMappingUnknownColumnBehavior: 指定发现自动映射目标未知列（或者未知属性类型）的行为
             * NONE:不做任何反应,默认
             * WARNING:输出提醒日志
             * FAILING:映射失败 (抛出 SqlSessionException)
             */
            //AutoMappingUnknownColumnBehavior为枚举类，doAction为枚举方法。
            /**
             * 根据配置的AutoMappingUnknownColumnBehavior枚举类，作出相应的处理，要么什么都不做，要
             * 么输出提醒日志，要么抛出SqlSessionException
             */
            configuration.getAutoMappingUnknownColumnBehavior()
              .doAction(mappedStatement, columnName, property, propertyType);
          }
        } else {
          //属性为null或者结果对象没有这个属性的setter方法
          /**
           * 根据配置的AutoMappingUnknownColumnBehavior枚举类，作出相应的处理，要么什么都不做，要
           * 么输出提醒日志，要么抛出SqlSessionException
           */
          configuration.getAutoMappingUnknownColumnBehavior()
            .doAction(mappedStatement, columnName, (property != null) ? property : propertyName, null);
        }
      }
      //保存结果对象的自动自动映射集合
      autoMappingsCache.put(mapKey, autoMapping);
    }
    return autoMapping;
  }

  /**
   * 对未能被映射列名映射关系集合进行尝试赋值，将结果集对应数据赋值到结果对象对应的属性中
   * @param rsw 结果集包装类对象
   * @param resultMap Mapper.xml的resultMap标签信息封装类对象
   * @param metaObject 结果元对象
   * @param columnPrefix 列名前缀
   * @return 成功根据自动映射关系集合的元素构建出指定java类对象标记，返回true；否则返回false
   */
  private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
    //获取未能被映射列名映射关系集合
    List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);
    //成功根据自动映射关系集合的元素构建出指定java类对象标记
    boolean foundValues = false;
    //如果自动映射关系集合不为空
    if (!autoMapping.isEmpty()) {
      //遍历自动映射关系集合
      for (UnMappedColumnAutoMapping mapping : autoMapping) {
        //从 ResultSet 中取出columnName对应数据，然后转换为 java 对象
        final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);
        //如果对象不为null
        if (value != null) {
          //只要有一个成功根据自动映射关系集合的元素构建出指定java类对象，标记就会变成true
          foundValues = true;
        }
        /**
         * isCallSetterOnNulls:
         * 指定当结果集中值为 null 的时候是否调用映射对象的 setter（map 对象时为 put）方法，
         * 这在依赖于 Map.keySet() 或 null 值初始化的时候比较有用。
         * 注意基本类型（int、boolean 等）是不能设置成 null 的。
         */
        //如果对象不为null,或者指定当结果集中值为null的时候允许调用映射对象的setter(map对象时为 put)方法,且不是基本类型
        if (value != null || (configuration.isCallSettersOnNulls() && !mapping.primitive)) {
          // gcode issue #377, call setter on nulls (value is not 'found')
          //将value赋值到结果对象中的mapping.property属性中
          metaObject.setValue(mapping.property, value);
        }
      }
    }
    return foundValues;
  }

  // MULTIPLE RESULT SETS

  /**
   * 将嵌套对象记录到外层元对象相应的属性中
   * @param rs 结果集
   * @param parentMapping 结果映射
   * @param rowValue 上一次嵌套的resultMap对象所构建出来的对象
   */
  private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
    //将外键对应结果集中的对象以及嵌套列名绑定到CacheKey对象中
    CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getForeignColumn());
    //获取该CacheKey对象对应的PendingRelation列表
    List<PendingRelation> parents = pendingRelations.get(parentKey);
    //如果该列表不为null
    if (parents != null) {
      //遍历PendingRelation列表
      for (PendingRelation parent : parents) {
        //如果列表中的每一项不为null 且 上一次嵌套的resultMap对象所构建出来的对象也不为null
        if (parent != null && rowValue != null) {
          /**
           * 上一次嵌套的resultMap对象所构建出来的对象放入元数据类型对象的相应属性中，如果为集合
           * 则在集合属性中添加该rowValue;如果不为集合，则直接将该属性设置为rowValue
           */
          linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
        }
      }
    }
  }

  /**
   * 添加 {@code parentMapping} 待定关系到待定关系映射集合 {@link #pendingRelations}
   * @param rs 结果集对象
   * @param metaResultObject 结果元对象
   * @param parentMapping 父级属性映射
   */
  private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping) throws SQLException {
    //为多个结果数据创建缓存Key
    CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());
    //创建一个待定关系类对象
    PendingRelation deferLoad = new PendingRelation();
    //将结果元对象赋值到待定关系类对象中
    deferLoad.metaObject = metaResultObject;
    //将父级属性映射赋值到待定关系类对象中
    deferLoad.propertyMapping = parentMapping;
    //从待定关系映射集合中获取cacheKey对应的待定关系集合，如果没有对应的待定关系集合会新建一个集合返回出去。
    List<PendingRelation> relations = pendingRelations.computeIfAbsent(cacheKey, k -> new ArrayList<>());
    // issue #255
    //添加待定关系到集合中
    relations.add(deferLoad);
    //从之前整合的结果映射对象映射集合中获取parentMapping.getResultSet()配置对应结果映射
    ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
    //如果没有找对应的结果映射
    if (previous == null) {
      //将结果映射加入到之前整合的结果映射对象映射集合中
      nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
    } else {
      //如果获取出来的映射结果和paraentMapping不是同一对象
      if (!previous.equals(parentMapping)) {
        //两个不同的属性不可以映射到相同的结果集
        throw new ExecutorException("Two different properties are mapped to the same resultSet");
      }
    }
  }

  /**
   * 为多个结果数据创建缓存Key
   * @param rs 结果集
   * @param resultMapping 结果属性映射
   * @param names 列名
   * @param columns 列名
   * @return 对应多个结果数据缓存Key
   */
  private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names, String columns) throws SQLException {
    //新建一个缓存Key对象
    CacheKey cacheKey = new CacheKey();
    //将结果属性映射作为缓存Key的一部分的
    cacheKey.update(resultMapping);
    //如果两个列名参数都不为null
    if (columns != null && names != null) {
      //因为有可能指代多个列名，所以用逗号进行分割
      String[] columnsArray = columns.split(",");
      ///因为有可能指代多个列名，所以用逗号进行分割
      String[] namesArray = names.split(",");
      //遍历列名数组
      for (int i = 0; i < columnsArray.length; i++) {
        //获取列名对应结果数据
        Object value = rs.getString(columnsArray[i]);
        if (value != null) {
          //将列名映射作为缓存Key的一部分的
          cacheKey.update(namesArray[i]);
          //将列名对应结果数据作为缓存Key的一部分的
          cacheKey.update(value);
        }
      }
    }
    return cacheKey;
  }

  //
  // INSTANTIATION & CONSTRUCTOR MAPPING
  //

  /**
   * 根据 {@code rsw} 和 {@code resultMap} 的配置构建结果对象
   * @param rsw 结果集包装类对象
   * @param resultMap Mapper.xml的resultMap标签信息封装类对象
   * @param lazyLoader  用来表示需要懒加载的属性集，本质是一个HashMap
   * @param columnPrefix 列名前缀
   * @return {@link ResultMap#getType()} 类型的对象
   */
  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
    this.useConstructorMappings = false; // reset previous mapping result 重置上一个映射结果
    //构造函数参数类型集合
    final List<Class<?>> constructorArgTypes = new ArrayList<>();
    //构造函数参数集合
    final List<Object> constructorArgs = new ArrayList<>();
    //取得构造函数所需的参数值去创建结果对象
    Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);
    //如果结果对象不为null但没有结果类型对应的TypeHandler
    if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      //获取属性结果映射关系集合，该集合不包括标记为构造函数的结果映射关系
      final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
      //变量属性结果映射关系
      for (ResultMapping propertyMapping : propertyMappings) {
        // issue gcode #109 && issue #149
        //如果配置了嵌套查询而且还配置了该属性为懒加载
        if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
          //将reusltObject改造成代理对象，从而处理懒加载
          resultObject = configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
          break;
        }
      }
    }
    //如果当前结果对象不为null且构造函数类型集合不为空，就将useConstructorMappings赋值为true
    this.useConstructorMappings = resultObject != null && !constructorArgTypes.isEmpty(); // set current mapping result 设置当前结果映射关系
    return resultObject;
  }

  /**
   * 取得构造函数所需的参数值去创建结果对象
   * @param rsw 结果集包装类对象
   * @param resultMap Mapper.xml的resultMap标签信息封装类对象
   * @param constructorArgTypes 构造函数参数类型集合
   * @param constructorArgs 构造函数参数集合
   * @param columnPrefix 列名前缀
   * @return {@link ResultMap#getType()} 类型的对象
   */
  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix)
    throws SQLException {
    //获取结果对象类型
    final Class<?> resultType = resultMap.getType();
    //构建结果对象类型的元对象
    final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
    //获取构造函数映射关系
    final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();
    //如果存在对应的TypeHandler
    if (hasTypeHandlerForResultObject(rsw, resultType)) {
      //创建原始的结果对象
      return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
      //构造函数映射关系不为空
    } else if (!constructorMappings.isEmpty()) {
      //根据构造函数映射构建的结果对象
      return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs, columnPrefix);
      //如果resultType是接口或者resultType有默认的构造函数
    } else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
      //让objectFactory创建通过默认构造函数resultType对象
      return objectFactory.create(resultType);
      //是否可以应用自动映射
    } else if (shouldApplyAutomaticMappings(resultMap, false)) {
      //取得构造函数所需的参数值去创建结果对象(自动映射)
      return createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs);
    }
    throw new ExecutorException("Do not know how to create an instance of " + resultType);
  }

  /**
   * 根据构造函数映射构建的结果对象
   * @param rsw 结果集包装类对象
   * @param resultType 结果类型
   * @param constructorMappings  构造函数参数映射关系集合
   * @param constructorArgTypes 构造函数参数类型集合
   * @param constructorArgs 构造函数参数集合
   * @param columnPrefix 列名前缀
   * @return 如果有构建出构造函数参数对象，就让objectFactory根据
   *        构造函数参数创建resultType对象，否则返回null
   */
  Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType, List<ResultMapping> constructorMappings,
                                         List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix) {
    //有构建出构造函数参数对象的标记
    boolean foundValues = false;
    //遍历构造函数参数映射关系
    for (ResultMapping constructorMapping : constructorMappings) {
      //获取构造函数参数映射关系对应java类型
      final Class<?> parameterType = constructorMapping.getJavaType();
      //获取构造函数参数映射关系对应的列名
      final String column = constructorMapping.getColumn();
      final Object value;
      try {
        //如果构造函数参数映射关系配置了嵌套查询的select标签ID
        if (constructorMapping.getNestedQueryId() != null) {
          //获取嵌套查询构造函数参数值
          value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
          //如果构造函数参数映射关系配置了嵌套的resultMapId
        } else if (constructorMapping.getNestedResultMapId() != null) {
          //获取构造函数参数映射关系配置的resultMapId对应的ResultMap对象
          final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
          //根据rsw 和 resultMap构建出 {@link ResultMap#getType()} 类型的对象
          value = getRowValue(rsw, resultMap, getColumnPrefix(columnPrefix, constructorMapping));
          //不存在嵌套的情况
        } else {
          //获取构造函数参数映射的TypeHandler
          final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
          //从 ResultSet 中取出columnName对应数据，然后转换为 java 对象
          value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
        }
      } catch (ResultMapException | SQLException e) {
        throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
      }
      //将构造函数参数类型添加到constructorArgTypes中
      constructorArgTypes.add(parameterType);
      //将构造函数参数对象添加到construtorArgs中
      constructorArgs.add(value);
      //只要有一个构造函数参数对象不为null，foundValue都会为true
      foundValues = value != null || foundValues;
    }
    //如果有构建出构造函数参数对象，就让objectFactory根据构造函数参数创建resultType对象，否则返回null
    return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  private Object createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) throws SQLException {
    final Constructor<?>[] constructors = resultType.getDeclaredConstructors();
    final Constructor<?> defaultConstructor = findDefaultConstructor(constructors);
    if (defaultConstructor != null) {
      return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, defaultConstructor);
    } else {
      for (Constructor<?> constructor : constructors) {
        if (allowedConstructorUsingTypeHandlers(constructor, rsw.getJdbcTypes())) {
          return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, constructor);
        }
      }
    }
    throw new ExecutorException("No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
  }

  private Object createUsingConstructor(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, Constructor<?> constructor) throws SQLException {
    boolean foundValues = false;
    for (int i = 0; i < constructor.getParameterTypes().length; i++) {
      Class<?> parameterType = constructor.getParameterTypes()[i];
      String columnName = rsw.getColumnNames().get(i);
      TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
      Object value = typeHandler.getResult(rsw.getResultSet(), columnName);
      constructorArgTypes.add(parameterType);
      constructorArgs.add(value);
      foundValues = value != null || foundValues;
    }
    return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  private Constructor<?> findDefaultConstructor(final Constructor<?>[] constructors) {
    if (constructors.length == 1) {
      return constructors[0];
    }

    for (final Constructor<?> constructor : constructors) {
      if (constructor.isAnnotationPresent(AutomapConstructor.class)) {
        return constructor;
      }
    }
    return null;
  }

  private boolean allowedConstructorUsingTypeHandlers(final Constructor<?> constructor, final List<JdbcType> jdbcTypes) {
    final Class<?>[] parameterTypes = constructor.getParameterTypes();
    if (parameterTypes.length != jdbcTypes.size()) {
      return false;
    }
    for (int i = 0; i < parameterTypes.length; i++) {
      if (!typeHandlerRegistry.hasTypeHandler(parameterTypes[i], jdbcTypes.get(i))) {
        return false;
      }
    }
    return true;
  }

  private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
    final Class<?> resultType = resultMap.getType();
    final String columnName;
    if (!resultMap.getResultMappings().isEmpty()) {
      final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
      final ResultMapping mapping = resultMappingList.get(0);
      columnName = prependPrefix(mapping.getColumn(), columnPrefix);
    } else {
      columnName = rsw.getColumnNames().get(0);
    }
    final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
    return typeHandler.getResult(rsw.getResultSet(), columnName);
  }

  //
  // NESTED QUERY
  //

  private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix) throws SQLException {
    final String nestedQueryId = constructorMapping.getNestedQueryId();
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);
    Object value = null;
    if (nestedQueryParameterObject != null) {
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      final Class<?> targetType = constructorMapping.getJavaType();
      final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
      value = resultLoader.loadResult();
    }
    return value;
  }

  private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    final String nestedQueryId = propertyMapping.getNestedQueryId();
    final String property = propertyMapping.getProperty();
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);
    Object value = null;
    if (nestedQueryParameterObject != null) {
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      final Class<?> targetType = propertyMapping.getJavaType();
      if (executor.isCached(nestedQuery, key)) {
        executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
        value = DEFERRED;
      } else {
        final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
        if (propertyMapping.isLazy()) {
          lazyLoader.addLoader(property, metaResultObject, resultLoader);
          value = DEFERRED;
        } else {
          value = resultLoader.loadResult();
        }
      }
    }
    return value;
  }

  private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    if (resultMapping.isCompositeResult()) {
      return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    } else {
      return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    }
  }

  private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    final TypeHandler<?> typeHandler;
    if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
      typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
    } else {
      typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
    }
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    final Object parameterObject = instantiateParameterObject(parameterType);
    final MetaObject metaObject = configuration.newMetaObject(parameterObject);
    boolean foundValues = false;
    for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
      final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
      final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
      final Object propValue = typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
      // issue #353 & #560 do not execute nested query if key is null
      if (propValue != null) {
        metaObject.setValue(innerResultMapping.getProperty(), propValue);
        foundValues = true;
      }
    }
    return foundValues ? parameterObject : null;
  }

  private Object instantiateParameterObject(Class<?> parameterType) {
    if (parameterType == null) {
      return new HashMap<>();
    } else if (ParamMap.class.equals(parameterType)) {
      return new HashMap<>(); // issue #649
    } else {
      return objectFactory.create(parameterType);
    }
  }

  //
  // DISCRIMINATOR
  //

  public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix) throws SQLException {
    Set<String> pastDiscriminators = new HashSet<>();
    Discriminator discriminator = resultMap.getDiscriminator();
    while (discriminator != null) {
      final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
      final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
      if (configuration.hasResultMap(discriminatedMapId)) {
        resultMap = configuration.getResultMap(discriminatedMapId);
        Discriminator lastDiscriminator = discriminator;
        discriminator = resultMap.getDiscriminator();
        if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
          break;
        }
      } else {
        break;
      }
    }
    return resultMap;
  }

  private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix) throws SQLException {
    final ResultMapping resultMapping = discriminator.getResultMapping();
    final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  private String prependPrefix(String columnName, String prefix) {
    if (columnName == null || columnName.length() == 0 || prefix == null || prefix.length() == 0) {
      return columnName;
    }
    return prefix + columnName;
  }

  //
  // HANDLE NESTED RESULT MAPS
  //

  private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    final DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    ResultSet resultSet = rsw.getResultSet();
    skipRows(resultSet, rowBounds);
    Object rowValue = previousRowValue;
    while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
      final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
      final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
      Object partialObject = nestedResultObjects.get(rowKey);
      // issue #577 && #542
      if (mappedStatement.isResultOrdered()) {
        if (partialObject == null && rowValue != null) {
          nestedResultObjects.clear();
          storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
      } else {
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
        if (partialObject == null) {
          storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
      }
    }
    if (rowValue != null && mappedStatement.isResultOrdered() && shouldProcessMoreRows(resultContext, rowBounds)) {
      storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
      previousRowValue = null;
    } else if (rowValue != null) {
      previousRowValue = rowValue;
    }
  }

  //
  // NESTED RESULT MAP (JOIN MAPPING)
  //

  private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String parentPrefix, CacheKey parentRowKey, boolean newObject) {
    boolean foundValues = false;
    for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
      final String nestedResultMapId = resultMapping.getNestedResultMapId();
      if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
        try {
          final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
          final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
          if (resultMapping.getColumnPrefix() == null) {
            // try to fill circular reference only when columnPrefix
            // is not specified for the nested result map (issue #215)
            Object ancestorObject = ancestorObjects.get(nestedResultMapId);
            if (ancestorObject != null) {
              if (newObject) {
                linkObjects(metaObject, resultMapping, ancestorObject); // issue #385
              }
              continue;
            }
          }
          final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
          final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
          Object rowValue = nestedResultObjects.get(combinedKey);
          boolean knownValue = rowValue != null;
          instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject); // mandatory
          if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
            rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);
            if (rowValue != null && !knownValue) {
              linkObjects(metaObject, resultMapping, rowValue);
              foundValues = true;
            }
          }
        } catch (SQLException e) {
          throw new ExecutorException("Error getting nested result map values for '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
        }
      }
    }
    return foundValues;
  }

  private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
    final StringBuilder columnPrefixBuilder = new StringBuilder();
    if (parentPrefix != null) {
      columnPrefixBuilder.append(parentPrefix);
    }
    if (resultMapping.getColumnPrefix() != null) {
      columnPrefixBuilder.append(resultMapping.getColumnPrefix());
    }
    return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
  }

  private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSetWrapper rsw) throws SQLException {
    Set<String> notNullColumns = resultMapping.getNotNullColumns();
    if (notNullColumns != null && !notNullColumns.isEmpty()) {
      ResultSet rs = rsw.getResultSet();
      for (String column : notNullColumns) {
        rs.getObject(prependPrefix(column, columnPrefix));
        if (!rs.wasNull()) {
          return true;
        }
      }
      return false;
    } else if (columnPrefix != null) {
      for (String columnName : rsw.getColumnNames()) {
        if (columnName.toUpperCase().startsWith(columnPrefix.toUpperCase())) {
          return true;
        }
      }
      return false;
    }
    return true;
  }

  private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix) throws SQLException {
    ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
    return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
  }

  //
  // UNIQUE RESULT KEY
  //

  private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
    final CacheKey cacheKey = new CacheKey();
    cacheKey.update(resultMap.getId());
    List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
    if (resultMappings.isEmpty()) {
      if (Map.class.isAssignableFrom(resultMap.getType())) {
        createRowKeyForMap(rsw, cacheKey);
      } else {
        createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
      }
    } else {
      createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
    }
    if (cacheKey.getUpdateCount() < 2) {
      return CacheKey.NULL_CACHE_KEY;
    }
    return cacheKey;
  }

  private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
    if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
      CacheKey combinedKey;
      try {
        combinedKey = rowKey.clone();
      } catch (CloneNotSupportedException e) {
        throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
      }
      combinedKey.update(parentRowKey);
      return combinedKey;
    }
    return CacheKey.NULL_CACHE_KEY;
  }

  private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
    List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
    if (resultMappings.isEmpty()) {
      resultMappings = resultMap.getPropertyResultMappings();
    }
    return resultMappings;
  }

  private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
    for (ResultMapping resultMapping : resultMappings) {
      if (resultMapping.isSimple()) {
        final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
        final TypeHandler<?> th = resultMapping.getTypeHandler();
        List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
        // Issue #114
        if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
          final Object value = th.getResult(rsw.getResultSet(), column);
          if (value != null || configuration.isReturnInstanceForEmptyRow()) {
            cacheKey.update(column);
            cacheKey.update(value);
          }
        }
      }
    }
  }

  private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, String columnPrefix) throws SQLException {
    final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
    List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
    for (String column : unmappedColumnNames) {
      String property = column;
      if (columnPrefix != null && !columnPrefix.isEmpty()) {
        // When columnPrefix is specified, ignore columns without the prefix.
        if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
          property = column.substring(columnPrefix.length());
        } else {
          continue;
        }
      }
      if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
        String value = rsw.getResultSet().getString(column);
        if (value != null) {
          cacheKey.update(column);
          cacheKey.update(value);
        }
      }
    }
  }

  private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
    List<String> columnNames = rsw.getColumnNames();
    for (String columnName : columnNames) {
      final String value = rsw.getResultSet().getString(columnName);
      if (value != null) {
        cacheKey.update(columnName);
        cacheKey.update(value);
      }
    }
  }

  private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
    final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
    if (collectionProperty != null) {
      final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
      targetMetaObject.add(rowValue);
    } else {
      metaObject.setValue(resultMapping.getProperty(), rowValue);
    }
  }

  private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
    final String propertyName = resultMapping.getProperty();
    Object propertyValue = metaObject.getValue(propertyName);
    if (propertyValue == null) {
      Class<?> type = resultMapping.getJavaType();
      if (type == null) {
        type = metaObject.getSetterType(propertyName);
      }
      try {
        if (objectFactory.isCollection(type)) {
          propertyValue = objectFactory.create(type);
          metaObject.setValue(propertyName, propertyValue);
          return propertyValue;
        }
      } catch (Exception e) {
        throw new ExecutorException("Error instantiating collection property for result '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
      }
    } else if (objectFactory.isCollection(propertyValue.getClass())) {
      return propertyValue;
    }
    return null;
  }

  private boolean hasTypeHandlerForResultObject(ResultSetWrapper rsw, Class<?> resultType) {
    if (rsw.getColumnNames().size() == 1) {
      return typeHandlerRegistry.hasTypeHandler(resultType, rsw.getJdbcType(rsw.getColumnNames().get(0)));
    }
    return typeHandlerRegistry.hasTypeHandler(resultType);
  }

}
