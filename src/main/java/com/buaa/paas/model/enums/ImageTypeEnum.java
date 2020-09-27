package com.buaa.paas.model.enums;

import lombok.Getter;

/**
 * 镜像类型枚举
 */
@Getter
public enum ImageTypeEnum {
    /**
     * 本地公共镜像
     */
    LOCAL_PUBLIC_IMAGE("公共镜像", 1),
    /**
     * 本地用户镜像
     */
    LOCAL_USER_IMAGE("用户镜像", 2);

    private String message;
    private int code;

    ImageTypeEnum(String message, int code) {
        this.message = message;
        this.code = code;
    }

    public static String getMessage(int code) {
        for (ImageTypeEnum enums : ImageTypeEnum.values()) {
            if (enums.getCode() == code) {
                return enums.message;
            }
        }
        return null;
    }
}
