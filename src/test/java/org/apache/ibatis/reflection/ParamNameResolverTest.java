package org.apache.ibatis.reflection;


import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.submitted.automapping.Article;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

class ParamNameResolverTest {

    @Test
    public void paramNameResolverTest() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setUseActualParamName(false);
        Method method = TestMapper.class.getMethod("select", Integer.class, String.class, RowBounds.class, Article.class);
        ParamNameResolver paramNameResolver = new ParamNameResolver(configuration, method);
        Field field = paramNameResolver.getClass().getDeclaredField("names");
        field.setAccessible(true);
        // 通过反射获取 ParamNameResolver 私有成员变量 names
        Object names = field.get(paramNameResolver);
        System.out.println("names: " + names);
    }

    private class TestMapper {
        public void select(@Param("id") Integer id, @Param("author") String author, RowBounds rb, Article article) {
        }
    }
}