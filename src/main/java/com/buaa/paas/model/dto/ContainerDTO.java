package com.buaa.paas.model.dto;

import com.buaa.paas.model.entity.Container;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 容器DTO
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ContainerDTO extends Container {
    /**
     * 所属项目名
     */
    private String projectName;

    /**
     * 状态名
     */
    private String statusName;

    /**
     * 所属用户名
     */
    private String username;
    /**
     * docker主机ip地址
     */
    private String ip;

}
