package com.buaa.paas.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 返回给前台的数据
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResultVO {
    private Integer code;

    private String message;

    private Object data;

    public Integer getCode() {
        return code;
    }
}
