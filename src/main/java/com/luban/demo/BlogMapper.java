package com.luban.demo;

public interface BlogMapper {

  Blog selectBlog(Integer id);

  Integer addBlog(Blog blog);

}
