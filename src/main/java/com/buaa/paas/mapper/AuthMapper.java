package com.buaa.paas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.buaa.paas.model.entity.Auth;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <p>
 * 登陆表 Mapper 接口
 * </p>
*/
@Mapper
public interface AuthMapper extends BaseMapper<Auth> {
    /**
     * 获取所有ID
     */
    List<String> listId();

    /**
     * 判断ID是否存在
     */
    boolean hasExist(String id);
}
