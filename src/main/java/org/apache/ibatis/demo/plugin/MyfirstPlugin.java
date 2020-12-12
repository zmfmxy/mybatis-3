package org.apache.ibatis.demo.plugin;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.*;

import java.util.Properties;

@Intercepts({
  @Signature(type = StatementHandler.class,method = "parameterize",args = java.sql.Statement.class)
})
public class MyfirstPlugin implements Interceptor {

  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    Object proceed = invocation.proceed();
    return proceed;
  }

  @Override
  public Object plugin(Object target) {
    Object wrap = Plugin.wrap(target, this);
    return wrap;
  }

  @Override
  public void setProperties(Properties properties) {
    System.out.println(properties);

  }
}
