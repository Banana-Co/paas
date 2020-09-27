package com.buaa.paas.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.buaa.paas.model.entity.Auth;

/**
 * <p>
 * 登陆表 服务类
 * </p>
*/
public interface AuthService extends IService<Auth> {
    /**
     * 根据ID获取用户
*/
    Auth getById(String id);

    /**
     * 根据用户名获取用户
*/
    Auth getByUsername(String username);

    /**
     * 根据邮件获取用户
*/
    Auth getByEmail(String email);

    boolean checkPassword(String username, String password);

    /**
     * 保存用户信息至数据库
*/
    boolean save(Auth auth);

    /**
     * 更新数据库用户信息
*/
    int update(Auth auth);

    /**
     * 发送注册邮件
*/
    Boolean sendRegisterEmail(String email);

    /**
     * 验证注册邮件
*/
    Boolean verifyRegisterEmail(String token);

    /**
     * 根据用户名删除
*/
    void deleteByUsername(String username);

    void deleteById(Auth login);

    /**
     * 清理用户缓存
*/
    void cleanLoginCache(Auth login);

    /**
     * 判断用户是否被冻结
     * @return 冻结返回true
     */
    boolean hasFreeze(String username);

    /**
     * 冻结用户
     * @return 成功数
     */
    int freezeUser(String[] ids);

    /**
     * 取消冻结用户
     * @return 成功数
     */
    int cancelFreezeUser(String[] ids);

    /**
     * 校验注册
     * @return
     */
    Boolean registerCheck(String username, String email);

    String getRoleName(String userId);
}