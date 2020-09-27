package com.buaa.paas.exception;

import com.buaa.paas.model.enums.ResultEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class CustomException extends Exception {
    private Integer code;

    public CustomException(ResultEnum resultEnum) {
        super(resultEnum.getMessage());
        this.code = resultEnum.getCode();
    }

    public CustomException(Integer code , String info) {
        super(info);
        this.code = code;
    }
}