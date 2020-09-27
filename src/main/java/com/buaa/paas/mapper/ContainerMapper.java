package com.buaa.paas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buaa.paas.model.entity.Container;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <p>
 * 用户容器表 Mapper 接口
 * </p>
*/
@Mapper
public interface ContainerMapper extends BaseMapper<Container> {
    /**
     * 获取某一用户所有容器
     * @param name 容器名
     */
    List<Container> listContainerByUserIdAndNameAndStatus(Page page, @Param("userId") String userId, @Param("name") String name, @Param("status") Integer status);

    /**
     * 判断容器是否属于指定用户
     */
    Boolean hasBelongSb(@Param("containerId") String containerId, @Param("userId") String userId);

    /**
     * 统计用户容器数目
     * @param status 容器状态，可选
     */
    Integer countByUserId(@Param("userId") String userId, @Param("status") Integer status);

    /**
     * 设置容器所属项目为NULL
     */
    Integer cleanProjectId(String projectId);
}