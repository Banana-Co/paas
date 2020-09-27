package com.buaa.paas.service;

/**
 */
public interface PortService {
    /**
     * 判断指定端口是否被占用
     */
    boolean hasUse(Integer port);

    /**
     * 返回一个可用端口
     * 端口范围：10000 ~65535
     */
    Integer randomPort();
}
