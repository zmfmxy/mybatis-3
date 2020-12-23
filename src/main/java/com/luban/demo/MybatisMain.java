package com.luban.demo;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;

public class MybatisMain {

  public static void main(String[] args) throws IOException {

    String resource = "mybatis-config.xml";
    InputStream inputStream = Resources.getResourceAsStream(resource);
    SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);

    SqlSession sqlSession = sqlSessionFactory.openSession();
    BlogMapper blogMapper = sqlSession.getMapper(BlogMapper.class);
    Blog blog  = new Blog();
    blog.setUsername("杨幂");
    blog.setContext("宫锁心玉");
    Integer i = blogMapper.addBlog(blog);
    System.out.println(i);
    sqlSession.rollback();

  }

  @Transactional
  public void test(){

  }

}
