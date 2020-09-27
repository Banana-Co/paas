package com.buaa.paas.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;


/**
 * 系统挂载 实体类
 */
@Data
public class SysVolume {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    /**
     * 目标Id
     */
    private String objId;
    /**
     * 挂载名称
     */
    private String name;
    /**
     * 容器内部目录
     */
    private String destination;
    /**
     *挂载目录
     */
    private String source;
    /**
     * 类别
     */
    private Integer type;
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
