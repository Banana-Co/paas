package com.buaa.paas.service.impl;

import com.buaa.paas.commons.util.HttpClientUtils;
import com.buaa.paas.commons.util.JwtUtils;
import com.buaa.paas.commons.util.jedis.JedisClient;
import com.buaa.paas.exception.UnauthorizedException;
import com.buaa.paas.model.entity.Auth;
import com.buaa.paas.model.vo.UserVO;
import com.buaa.paas.service.AuthService;
import com.buaa.paas.service.JwtService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.FastDateFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JWT 服务
 * @author jitwxs
 * @since 2018/6/27 16:36
 */
@Slf4j
@Service
public class JwtServiceImpl implements JwtService {
    @Autowired
    private AuthService authService;
    @Autowired
    private JedisClient jedisClient;

    @Value("${redis.token.key}")
    private String key;

    /**
     * JWT有效时间（单位：小时）
     */
    @Value("${token.expire}")
    private Integer expireHour;

    /**
     * 生成token
     * 确保一个用户只有一个token有效，强依赖Redis
     * @author jitwxs
     * @since 2018/7/13 21:11
     */
    @Override
    public String genToken(String username) {
        Auth login = authService.getByUsername(username);

        // 1、生成token
        Map<String,Object> map = new HashMap<>(16);
        map.put("uid", login.getId());
        map.put("rid", login.getRoleId());
        map.put("timestamp", System.currentTimeMillis());

        String token = JwtUtils.sign(map, 6 * 3600 * 1000);

        // 2、清理该用户其他token，确保只有一个token有效
        try {
            jedisClient.hdel(key, username);
            jedisClient.hset(key, username, token);

            return token;
        } catch (Exception e) {
            log.error("token缓存出现错误，错误位置：{}，错误栈：{}", "JwtServiceImpl.genToken()", HttpClientUtils.getStackTraceAsString(e));
            return null;
        }
    }

    @Override
    public Map checkToken(String token) {
        try {
            // 1、判断Token是否存在于Redis
            List<String> tokens = jedisClient.hvals(key);
            if(!tokens.contains(token)) {
                throw new UnauthorizedException();
            }

            // 2、判断Token是否过期
            Map map = JwtUtils.unSign(token);
            if(map == null) {
                throw new UnauthorizedException();
            }

            return map;
        } catch (UnauthorizedException e) {
            throw new UnauthorizedException();
        } catch (Exception e) {
            log.error("token缓存出现错误，错误位置：{}，错误栈：{}", "JwtServiceImpl.checkToken()", HttpClientUtils.getStackTraceAsString(e));
            throw new UnauthorizedException();
        }
    }

    @Override
    public UserVO getUserInfo(String token) {
        Map map = JwtUtils.unSign(token);
        String userId = (String)map.get("uid");
        Integer roleId = (Integer)map.get("rid");

        Auth login = authService.getById(userId);

        UserVO userVO = new UserVO();
        userVO.setUserId(userId);
        userVO.setRoleId(roleId);
        userVO.setUsername(login.getUsername());
        userVO.setEmail(login.getEmail());

        return userVO;
    }

    @Override
    public Map<String, Map<String, String>> listToken() {
        FastDateFormat format = FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss");
        Map<String, Map<String, String>> map = new HashMap<>(16);
        try {
            // 取出所有用户
            Set<String> fields = jedisClient.hkeys(key);

            for(String uid : fields) {
                String token = jedisClient.hget(key, uid);
                Map<String, String> tokenMap = new HashMap<>(16);
                tokenMap.put("token", token);

                try {
                    Map data = checkToken(token);
                    long timestamp = (long) data.get("timestamp");
                    tokenMap.put("createDate", format.format(timestamp));
                } catch (Exception e) {
                    tokenMap.put("createDate", "已过期");
                }

                map.put(uid, tokenMap);
            }

            return map;
        } catch (Exception e) {
            log.error("token缓存出现错误，错误位置：{}，错误栈：{}", "JwtServiceImpl.listToken()", HttpClientUtils.getStackTraceAsString(e));
            throw new UnauthorizedException();
        }
    }

    @Override
    public void deleteToken(String username) {
        try {
            jedisClient.hdel(key, username);
        } catch (Exception e) {
            log.error("token缓存出现错误，错误位置：{}，错误栈：{}", "JwtServiceImpl.deleteToken()", HttpClientUtils.getStackTraceAsString(e));
            throw new UnauthorizedException();
        }
    }
}
