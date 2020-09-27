package com.buaa.paas.security;

import com.buaa.paas.model.entity.Auth;
import com.buaa.paas.model.enums.RoleEnum;
import com.buaa.paas.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;

/**
 */
@Service("userDetailsService")
public class CustomUserDetailsService implements UserDetailsService {
    @Autowired
    private AuthService authService;

    @Override
    public UserDetails loadUserByUsername(String s) throws UsernameNotFoundException {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        Auth auth = authService.getByUsername(s);

        // 判断用户是否存在
        if(auth == null) {
            throw new UsernameNotFoundException("用户名不存在");
        }

        // 添加权限
        String roleName = RoleEnum.getMessage(auth.getRoleId());
        authorities.add(new SimpleGrantedAuthority(roleName));

        // 返回UserDetails实现类
        return new User(auth.getUsername(), auth.getPassword(), authorities);
    }
}