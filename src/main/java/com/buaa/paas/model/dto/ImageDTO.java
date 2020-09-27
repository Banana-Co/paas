package com.buaa.paas.model.dto;

import com.buaa.paas.model.entity.Image;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class ImageDTO extends Image {
    /**
     * 所属用户名
     */
    private String username;
}
