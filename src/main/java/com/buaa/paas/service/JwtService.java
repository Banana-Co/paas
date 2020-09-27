package com.buaa.paas.service;

import com.buaa.paas.model.vo.UserVO;

import java.util.Map;

public interface JwtService {

    /**
     * 生成Token
     * @author jitwxs
     * @since 2018/7/13 21:13
     */
    String genToken(String username);

    /**
     * 校验Token
     * @author jitwxs
     * @since 2018/7/13 21:13
     * @return
     */
    Map checkToken(String token);

    /**
     * 读取用户信息
     * @author jitwxs
     * @since 2018/7/13 21:13
     */
    UserVO getUserInfo(String token);

    /**
     * 获取所有token
     * @author jitwxs
     * @since 2018/7/14 8:54
     * @return
     */
    Map<String, Map<String, String>> listToken();

    /**
     * 删除Token
     * @author jitwxs
     * @since 2018/7/14 9:32
     */
    void deleteToken(String username);
}
