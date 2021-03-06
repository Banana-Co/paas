package com.buaa.paas.service.impl;

import com.buaa.paas.commons.util.RandomUtils;
import com.buaa.paas.service.PortService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

/**
 * 端口服务
*/
@Service
@Slf4j
public class PortServiceImpl implements PortService {
    @Value("${docker.server.address}")
    private String host;

    /**
     * 端口是否使用
     * @return 使用：True；
     */
    @Override
    public boolean hasUse(Integer port) {
        boolean flag = true;
        try {
            flag = isPortUsing(host, port);
        } catch (Exception e) {
        }

        return flag;
    }

    @Override
    public Integer randomPort() {
        int port;
        do {
            port = RandomUtils.integer(10000, 65535);
        }while (hasUse(port));
        return port;
    }


    /**
     * 端口是否使用
* @return 使用：True；
     */
    private static boolean isPortUsing(String host,int port) throws UnknownHostException {
        boolean flag = false;
        InetAddress theAddress = InetAddress.getByName(host);
        try {
            Socket socket = new Socket(theAddress,port);
            flag = true;
        } catch (IOException e) {
            // 异常说明被占用
        }
        return flag;
    }
}
