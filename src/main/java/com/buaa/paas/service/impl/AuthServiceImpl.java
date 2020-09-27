package com.buaa.paas.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buaa.paas.commons.activemq.MQProducer;
import com.buaa.paas.commons.activemq.Task;
import com.buaa.paas.commons.util.*;
import com.buaa.paas.commons.util.jedis.JedisClient;
import com.buaa.paas.model.entity.Auth;
import com.buaa.paas.model.enums.RoleEnum;
import com.buaa.paas.exception.CustomException;
import com.buaa.paas.mapper.AuthMapper;
import com.buaa.paas.service.AuthService;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.command.ActiveMQQueue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import javax.jms.Destination;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 登陆表 服务实现类
 * </p>
*/
@Service
@Slf4j
public class AuthServiceImpl extends ServiceImpl<AuthMapper, Auth> implements AuthService {
    @Autowired
    private AuthMapper authMapper;
//    @Autowired
//    private SysLogService // sysLogService;
    @Autowired
    JavaMailSender mailSender;
    @Autowired
    private TemplateEngine templateEngine;
    @Autowired
    private JedisClient jedisClient;
    @Autowired
    private MQProducer mqProducer;
    @Autowired
    private HttpServletRequest request;

    @Value("${redis.login.key}")
    private String key;
    private final String ID_PREFIX = "ID:";
    private final String USERNAME_PREFIX = "NAME:";
    private final String EMAIL_PREFIX = "EMAIL:";

    @Value("${redis.register.email.key}")
    private String registerEmailKey;
    @Value("${redis.register.email.expire}")
    private int registerEmailExpire;

    @Value("${spring.mail.username}")
    private String senderAddress;

    @Value("${server.addr}")
    private String serverIp;

    @Override
    public Auth getById(String id) {
        String field = ID_PREFIX + id;
        try {
            String res = jedisClient.hget(key, field);
            if (StringUtils.isNotBlank(res)) {
                return JsonUtils.jsonToObject(res, Auth.class);
            }
        } catch (Exception e) {
            log.error("缓存读取异常，错误位置：SysLoginServiceImpl.getById()");
        }

        Auth login = authMapper.selectById(id);
        // 如果用户不存在，跳过缓存
        if (login == null) {
            return null;
        }

        try {
            jedisClient.hset(key, field, JsonUtils.objectToJson(login));
        } catch (Exception e) {
            log.error("缓存存储异常，错误位置：SysLoginServiceImpl.getById()");
        }

        return login;
    }

    /**
     * 根据用户名查找
*/
    @Override
    public Auth getByUsername(String username) {
        if (StringUtils.isBlank(username)) {
            return null;
        }

        String field = USERNAME_PREFIX + username;
        try {
            String res = jedisClient.hget(key, field);
            if (StringUtils.isNotBlank(res)) {
                return JsonUtils.jsonToObject(res, Auth.class);
            }
        } catch (Exception e) {
            log.error("缓存读取异常，错误位置：SysLoginServiceImpl.getByUsername()");
        }

        List<Auth> list = authMapper.selectList(new QueryWrapper<Auth>().eq("username", username));
        Auth first = CollectionUtils.getListFirst(list);

        // 如果用户不存在，跳过缓存
        if (first == null) {
            return null;
        }

        try {
            jedisClient.hset(key, field, JsonUtils.objectToJson(first));
        } catch (Exception e) {
            log.error("缓存存储异常，错误位置：SysLoginServiceImpl.getByUsername()");
        }

        return first;
    }

    /**
     * 根据邮箱查找
*/
    @Override
    public Auth getByEmail(String email) {
        if (StringUtils.isBlank(email)) {
            return null;
        }

        String field = EMAIL_PREFIX + email;
        try {
            String res = jedisClient.hget(key, field);
            if (StringUtils.isNotBlank(res)) {
                return JsonUtils.jsonToObject(res, Auth.class);
            }
        } catch (Exception e) {
            log.error("缓存读取异常，错误位置：SysLoginServiceImpl.getByEmail()");
        }

        List<Auth> list = authMapper.selectList(new QueryWrapper<Auth>().eq("email", email));
        Auth first = CollectionUtils.getListFirst(list);

        // 如果用户不存在，跳过缓存
        if (first == null) {
            return null;
        }

        try {
            jedisClient.hset(key, field, JsonUtils.objectToJson(first));
        } catch (Exception e) {
            log.error("缓存存储异常，错误位置：SysLoginServiceImpl.getByEmail()");
        }

        return first;
    }

    /**
     * 验证密码
*/
    @Override
    public boolean checkPassword(String username, String password) {
        Auth login = getByUsername(username);
        if (login == null) {
            return false;
        }
        return new BCryptPasswordEncoder().matches(password, login.getPassword());
    }

    @Override
    public boolean save(Auth auth) {
        // 加密密码
        if(StringUtils.isNotBlank(auth.getPassword())) {
            auth.setPassword(new BCryptPasswordEncoder().encode(auth.getPassword()));
        }
        // 用户角色默认为User
        auth.setRoleId(RoleEnum.ROLE_USER.getCode());
        int i = authMapper.insert(auth);
        return i == 1;
    }

    @Override
    public int update(Auth auth) {
        int i = authMapper.updateById(auth);
        cleanLoginCache(auth);
        return i;
    }

    /**
     * 发送注册邮件
*/
    @Override
    public Boolean sendRegisterEmail(String email) {
        MimeMessage mimeMailMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper;

        // 生成token，注意jwt过期时间为ms
        Map<String, Object> map = new HashMap<>(16);
        map.put("email", email);
        map.put("timestamp", System.currentTimeMillis());
        String token = JwtUtils.sign(map, registerEmailExpire * 1000);

        try {
            // 将token放入缓存
            jedisClient.sadd(registerEmailKey, token);
        } catch (Exception e) {
            log.error("缓存存储异常，错误位置：SysLoginServiceImpl.sendRegisterEmail()");
            // 因为邮箱注册依赖redis，因此redis失效不允许注册
            return false;
        }

        try {
            helper = new MimeMessageHelper(mimeMailMessage, true);
            helper.setFrom(senderAddress);
            helper.setTo(email);
            helper.setSubject("注册PaaS平台");

            Context context = new Context();
            // 去除token前缀
            Map<String,Object> vars = new HashMap<>();
            vars.put("registerUrl",token.substring(7));
            vars.put("serverIp",serverIp);
            //context.setVariable("registerUrl", token.substring(7));
            context.setVariables(vars);
            String emailContent = templateEngine.process("mail", context);
            helper.setText(emailContent, true);

            mailSender.send(mimeMailMessage);

            // 发送延时消息
            Map<String,String> maps = new HashMap<>(16);
            maps.put("email",email);
            Task task = new Task("邮箱注册任务", maps);
            Destination destination = new ActiveMQQueue("MQ_QUEUE_REGISTER");
            mqProducer.delaySend(destination, JsonUtils.objectToJson(task), (long)registerEmailExpire * 1000);

            return true;
        } catch (MessagingException e) {
            log.error("发送邮件异常，错误位置：SysLoginServiceImpl.sendEmail()，目标邮箱：{}", email);
        }
        return false;
    }

    @Override
    @Transactional(rollbackFor = CustomException.class)
    public Boolean verifyRegisterEmail(String token) {
        String email;

        // 1、加上token前缀
        token = "Bearer " + token;
        // 2、从redis中取
        try {
            // 判断是否存在
            Boolean b = jedisClient.sismember(registerEmailKey, token);
            if (!b) {
                return false;
            }

            Map map = JwtUtils.unSign(token);
            // 判断是否过期
            long timestamp = (long) map.get("timestamp");
            long now = System.currentTimeMillis();
            if (registerEmailExpire < (now - timestamp) / 1000) {
                return false;
            }
            email = (String) map.get("email");
        } catch (Exception e) {
            log.error("缓存读取异常，或验证邮箱token错误，错误位置：SysLoginServiceImpl.verifyRegisterEmail()");
            return false;
        }

        // 3、取消用户冻结状态
        Auth login = getByEmail(email);
        if (login != null) {
            login.setHasFreeze(false);
            authMapper.updateById(login);
        } else {
            log.error("验证邮箱用户不存在，错误位置：SysLoginServiceImpl.verifyRegisterEmail()，目标email：{}", email);
            return false;
        }

        // 4、移除token 并 删除用户信息缓存
        try {
            jedisClient.srem(registerEmailKey, token);
            cleanLoginCache(login);
        } catch (Exception e) {
            log.error("缓存存储异常，错误位置：SysLoginServiceImpl.verifyRegisterEmail()");
        }

        return true;
    }


    @Override
    public void deleteByUsername(String username) {
        List<Auth> list = authMapper.selectList(new QueryWrapper<Auth>().eq("username", username));
        Auth first = CollectionUtils.getListFirst(list);
        if (first != null) {
            authMapper.delete(new QueryWrapper<Auth>().eq("username", username));
            // 清理缓存
            cleanLoginCache(first);
        }
    }

    @Override
    public void deleteById(Auth login) {
        authMapper.deleteById(login);
        // 清理缓存
        cleanLoginCache(login);
    }

    @Override
    public void cleanLoginCache(Auth login) {
        try {
            if (StringUtils.isNotBlank(login.getId())) {
                jedisClient.hdel(key, ID_PREFIX + login.getId());
            }
            if (StringUtils.isNotBlank(login.getUsername())) {
                jedisClient.hdel(key, USERNAME_PREFIX + login.getUsername());
            }
            if (StringUtils.isNotBlank(login.getEmail())) {
                jedisClient.hdel(key, EMAIL_PREFIX + login.getEmail());
            }
        } catch (Exception e) {
            log.error("缓存删除异常，错误位置：SysLoginServiceImpl.cleanLoginCache()");
        }
    }

    @Override
    public boolean hasFreeze(String username) {
        Auth login = getByUsername(username);

        if(login != null) {
            return login.getHasFreeze();
        }
        return false;
    }

    @Override
    public int freezeUser(String[] ids) {
        int count = 0;
        for(String id : ids) {
            Auth login = getById(id);

            if(login != null && !login.getHasFreeze() && login.getRoleId() == RoleEnum.ROLE_USER.getCode()) {
                login.setHasFreeze(true);
                // 更新数据
                update(login);

                count++;
            }
        }
        // 写入日志
        // sysLogService.saveLog(request, SysLogTypeEnum.FREEZE_USER);

        return count;
    }

    @Override
    public int cancelFreezeUser(String[] ids) {
        int count = 0;
        for(String id : ids) {
            Auth login = getById(id);

            if(login != null && login.getHasFreeze() && login.getRoleId() == RoleEnum.ROLE_USER.getCode()) {
                login.setHasFreeze(false);
                // 更新数据
                update(login);
                count++;
            }
        }
        // 写入日志
        // sysLogService.saveLog(request, SysLogTypeEnum.CANCEL_FREEZE_USER);

        return count;
    }

    @Override
    public Boolean registerCheck(String username, String email) {
        if(StringUtils.isBlank(username, email)) {
            return false;
        }
        if(getByUsername(username) != null) {
            return false;
        }

        // 校验邮箱
        if(!StringUtils.isEmail(email)) {
            return false;
        }
        if(getByEmail(email) != null) {
            return false;
        }

        return true;
    }

    @Override
    public String getRoleName(String userId) {
        Auth auth = getById(userId);
        return RoleEnum.getMessage(auth.getRoleId());
    }
}