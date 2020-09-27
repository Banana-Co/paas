package com.buaa.paas.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buaa.paas.model.dto.ImageDTO;
import com.buaa.paas.model.entity.Image;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
*/
@Mapper
public interface ImageMapper extends BaseMapper<Image> {
    /**
     * 获取本地公共镜像
     */
    List<ImageDTO> listLocalPublicImage(Page<ImageDTO> page, @Param("name") String name);

    /**
     * 获取本地用户镜像
     */
    List<ImageDTO> listLocalUserImage(Page<ImageDTO> page, @Param("name") String name);

    /**
     * 获取当前用户所有镜像
     */
    List<Image> listSelfImage(@Param("userId") String userId, Page<Image> page);
}
