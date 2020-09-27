package com.buaa.paas.filter;

import com.buaa.paas.commons.util.JsonUtils;
import com.buaa.paas.commons.util.SpringBeanFactoryUtils;
import com.buaa.paas.commons.util.StringUtils;
import com.buaa.paas.model.entity.Auth;
import com.buaa.paas.model.enums.ResultEnum;
import com.buaa.paas.model.vo.UserVO;
import com.buaa.paas.service.AuthService;
import com.buaa.paas.service.JwtService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

/**
 * 该filter继承自UsernamePasswordAuthenticationFilter
 * 在验证用户名密码正确后，生成一个token，并将token返回给客户端
 */
public class JwtLoginFilter extends UsernamePasswordAuthenticationFilter {
    private AuthenticationManager authenticationManager;

    public JwtLoginFilter(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    /**
     * 该方法在Spring Security验证前调用
     * 将用户信息从request中取出，并放入authenticationManager中
     */
    @Override
    public Authentication attemptAuthentication(HttpServletRequest req, HttpServletResponse res) throws AuthenticationException {
        String username = req.getParameter("username");
        String password = req.getParameter("password");

        if(StringUtils.isBlank(username, password)) {
            return null;
        }

        try {
            // 将用户信息放入authenticationManager
            password = getEncryPassword(username, password);

            return authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            username,
                            password,
                            Collections.emptyList())
            );
        } catch (Exception e) {
            try {
                req.setAttribute("ERR_MSG", ResultEnum.LOGIN_ERROR);
                req.getRequestDispatcher("/auth/error").forward(req, res);
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        }
        return null;
    }

    /**
     * 该方法在Spring Security验证成功后调用
     * 在这个方法里生成JWT token，并返回给用户
     */
    @Override
    protected void successfulAuthentication(HttpServletRequest request, HttpServletResponse response, FilterChain chain, Authentication auth) throws IOException, ServletException {
        String username = ((User)auth.getPrincipal()).getUsername();

        // 判断用户是否被冻结
        AuthService authService = SpringBeanFactoryUtils.getBean(AuthService.class);
        if(authService.hasFreeze(username)) {
            request.setAttribute("ERR_MSG", ResultEnum.LOGIN_FREEZE);
            request.getRequestDispatcher("/auth/error").forward(request,response);
        }

        // 生成Token
        JwtService jwtService = SpringBeanFactoryUtils.getBean(JwtService.class);
        String token = jwtService.genToken(username);

        // 将token放入响应头中
        response.addHeader("Authorization", token);
        response.setHeader("Access-Control-Expose-Headers","Authorization");

        response.setContentType("text/html;charset=utf-8");
        // 读取用户信息
        UserVO userVO = jwtService.getUserInfo(token);
        String json = JsonUtils.objectToJson(userVO);
        response.getWriter().write(json);
    }

    /**
     * 获取加密的密码
     * 如果用户身份验证失败，返回原密码
     */
    private String getEncryPassword(String username, String password) {
        AuthService authService = SpringBeanFactoryUtils.getBean(AuthService.class);
        if(authService.checkPassword(username, password)) {
            Auth auth = authService.getByUsername(username);
            return auth.getPassword();
        } else {
            return password;
        }
    }
}
