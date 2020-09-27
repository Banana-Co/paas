package com.buaa.paas.model.vo;

import lombok.Data;

@Data
public class UserVO {
    private String userId;

    private String username;

    private String email;

    private Integer roleId;
}
