package com.buaa.paas.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p>
 * 用户容器表
 * </p>
*/
@Data
public class Container implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 容器ID
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    /**
     * 项目ID
     */
    private String projectId;
    /**
     * 用户ID
     */
    private String userId;
    /**
     * 容器名
     */
    private String name;
    /**
     * 执行命令
     */
    private String command;
    /**
     * 端口
     */
    private String port;
    /**
     * 镜像名
     */
    private String image;
    /**
     * 容器状态
     */
    private Integer status;
    /**
     * 环境参数
     */
    private String env;
    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createDate;
    /**
     * 修改时间
     */
    @TableField(update = "now()")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date updateDate;
}
