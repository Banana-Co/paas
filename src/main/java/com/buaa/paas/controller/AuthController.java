package com.buaa.paas.controller;

import com.buaa.paas.commons.util.StringUtils;
import com.buaa.paas.exception.BadRequestException;
import com.buaa.paas.exception.InternalServerErrorException;
import com.buaa.paas.exception.UnauthorizedException;
import com.buaa.paas.model.entity.Auth;
import com.buaa.paas.model.vo.UserVO;
import com.buaa.paas.service.AuthService;
import com.buaa.paas.service.JwtService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 鉴权Controller
 * 该Controller无需权限即可访问
 */
@RestController
@RequestMapping("/auth")
@Slf4j
public class AuthController {
    @Autowired
    private AuthService authService;
    @Autowired
    private JwtService jwtService;
    @Value("${frontend.addr}")
    private String frontendAddr;

    /**
     * 注册校验
     * @return
     */
    @PostMapping("/register/check")
    public Boolean checkRegister(String username, String email) {
        return authService.registerCheck(username, email);
    }

    @PostMapping("/check_token")
    public void checkToken(@RequestParam String token) {
        jwtService.checkToken(token);
    }

    @GetMapping("/info")
    public UserVO getInfo(@RequestParam String token) {
        return jwtService.getUserInfo(token);
    }


    /**
     * 用户注册
     */
    @PostMapping("/register")
    public void register(String username, String password, String email) {
        if(StringUtils.isBlank(username,password,email)) {
            throw new BadRequestException("用户信息不合法");
        }

        // 1、校验用户名、密码
        if (!authService.registerCheck(username, email))
            throw new BadRequestException("用户信息不合法，或用户名和邮箱已被占用");

        // 2、生成用户
        Auth auth = new Auth(username,password,email);
        // 设置为冻结状态
        auth.setHasFreeze(true);
        authService.save(auth);

        // 3、发送邮件
        Boolean b = authService.sendRegisterEmail(email);
        if (!b)
            throw new InternalServerErrorException("邮件发送错误");
    }

    /**
     * 邮件验证
     */
    @GetMapping("/email")
    public void email(String token, HttpServletResponse response) {
        Boolean b = authService.verifyRegisterEmail(token);

        String subject = b ? "注册成功" : "注册失败";
        String content = b ? "欢迎注册PASS平台，点击此处进入" : "用户已注册或邮件验证已过期，请重新注册";
        try {
            response.setContentType("text/html;charset=utf-8");
            response.getWriter().write("<!DOCTYPE html>\n" +
                    "<html>\n" +
                    "<head>\n" +
                    "    <meta charset='utf-8'>\n" +
                    "    <title></title>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "<div style='position: absolute; bottom:70%;left:50%;margin-left:-60px;'>\n" +
                    "    <h1>"+ subject +"</h1>\n" +
                    "</div>\n" +
                    "<div style='position: absolute; bottom:65%;left:45.5%;margin-left:-60px;'>\n" +
                    "    <a href='"+frontendAddr+"'>" + content + "</a>\n" +
                    "</div>\n" +
                    "</body>\n" +
                    "</html>");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 失败方法
     */
    @RequestMapping("/error")
    public void loginError(HttpServletRequest request) {
        // 如果Spring Security中有异常，输出
        AuthenticationException exception = (AuthenticationException)request.getSession().getAttribute("SPRING_SECURITY_LAST_EXCEPTION");
        if(exception != null) {
            throw new UnauthorizedException(exception.toString());
        }

        exception = (AuthenticationException)request.getSession().getAttribute("ERR_MSG");
        if(exception != null) {
            throw new UnauthorizedException(exception.toString());
        }
    }
}