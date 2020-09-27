package com.buaa.paas.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buaa.paas.commons.convert.UserContainerDTOConvert;
import com.buaa.paas.commons.util.CollectionUtils;
import com.buaa.paas.commons.util.ResultVOUtils;
import com.buaa.paas.commons.util.StringUtils;
import com.buaa.paas.exception.BadRequestException;
import com.buaa.paas.exception.ForbiddenException;
import com.buaa.paas.model.dto.ContainerDTO;
import com.buaa.paas.model.entity.Container;
import com.buaa.paas.model.enums.ContainerOpEnum;
import com.buaa.paas.model.enums.ContainerStatusEnum;
import com.buaa.paas.model.enums.ResultEnum;
import com.buaa.paas.model.enums.RoleEnum;
import com.buaa.paas.service.AuthService;
import com.buaa.paas.service.ContainerService;
import com.spotify.docker.client.messages.TopResults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 容器Controller
*/
@Slf4j
@RestController
@RequestMapping("/container")
public class ContainerController {
    @Autowired
    private ContainerService containerService;
    @Autowired
    private AuthService loginService;

    @Value("${docker.server.address}")
    private String dockerAddress;
    @Value("${docker.server.port}")
    private String dockerPort;
    @Value("${server.ip}")
    private String serverIp;
    @Value("${server.port}")
    private String serverPort;
    @Autowired
    private UserContainerDTOConvert dtoConvert;

    /**
     * 获取容器
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public ContainerDTO getById(@RequestAttribute String uid, @PathVariable String id) {
        if (!containerService.checkPermission(uid, id))
            throw new ForbiddenException("无权限访问");

        return containerService.getById(id);
    }

    /**
     * 获取容器状态（包含状态同步）
     * @return
     */
    @GetMapping("/status/{id}")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public int getStatus(@PathVariable String id) {
        ContainerStatusEnum status = containerService.getStatus(id);

        return status.getCode();
    }

    /**
     * 获取容器列表
     * 普通用户获取本人容器，系统管理员获取所有容器
     * @param name 容器名
     * @return
     */
    @GetMapping("/list")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public Page<ContainerDTO> listContainer(@RequestAttribute String uid, String name, Integer status,
                                            @RequestParam(defaultValue = "1") Integer current,
                                            @RequestParam(defaultValue = "10") Integer size) {
        // 鉴权
        String roleName = loginService.getRoleName(uid);
        // 角色无效
        if (StringUtils.isBlank(roleName)) {
            throw new ForbiddenException("用户身份无效");
        }

        Page<Container> page = new Page<Container>(current, size, false);
        Page<ContainerDTO> selectPage = null;

        if (RoleEnum.ROLE_USER.getMessage().equals(roleName)) {
            selectPage = containerService.listContainerByUserId(uid, name, status, page);
        } else if (RoleEnum.ROLE_SYSTEM.getMessage().equals(roleName)) {
            selectPage = containerService.listContainerByUserId(null, name, status, page);
        }

        return selectPage;
    }

    /**
     * 创建容器【WebSocket】
* @param imageId        镜像ID 必填
     * @param containerName  容器名 必填
     * @param projectId      所属项目 必填
     * @param portMapStr     端口映射，Map<String,String> JSON字符串
     * @param cmdStr         执行命令，如若为空，使用默认的命令，多个分号连接
     * @param envStr         环境变量，多个分号连接
     * @param destinationStr 容器内部目录，多个分号连接
     */
    @PostMapping("/create")
    @PreAuthorize("hasRole('ROLE_USER')")
    public void createContainer(String imageId, String containerName, String projectId,
                                    String portMapStr, String cmdStr, String envStr, String destinationStr,
                                    @RequestAttribute String uid, HttpServletRequest request) {
        // 输入验证
        if (StringUtils.isBlank(imageId, containerName, projectId)) {
            throw new ForbiddenException("不合法的创建容器请求");
        }

        // 前端传递map字符串
        Map<String, String> portMap;
        try {
            portMap = CollectionUtils.mapJson2map(portMapStr);
        } catch (Exception e) {
            log.error("Json格式解析错误，错误位置：{}，错误信息：{}", "ContainerController.createContainer()", e.getMessage());
            throw new ForbiddenException("不合法的创建容器请求");
        }

        // 前台字符串转换
        String[] cmd = CollectionUtils.str2Array(cmdStr, ";"),
                env = CollectionUtils.str2Array(envStr, ";"),
                destination = CollectionUtils.str2Array(destinationStr, ";");

        // 创建校验
        if (!containerService.createContainerCheck(uid, imageId, portMap, projectId))
            throw new ForbiddenException("不合法的创建容器请求");

        containerService.createContainerTask(uid, imageId, cmd, portMap, containerName, projectId, env, destination, request);
    }

    /**
     * 开启容器【WebSocket】
*/
    @GetMapping("/start/{containerId}")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public void startContainer(@RequestAttribute String uid, @PathVariable String containerId) {
        if (containerService.hasAllowOp(uid, containerId, ContainerOpEnum.START))
            containerService.startContainerTask(uid, containerId);
        else
            throw new ForbiddenException("无法进行此操作");
    }

    /**
     * 暂停容器【WebSocket】
*/
    @GetMapping("/pause/{containerId}")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public void pauseContainer(@RequestAttribute String uid, @PathVariable String containerId) {
        if (containerService.hasAllowOp(uid, containerId, ContainerOpEnum.PAUSE)) {
            containerService.pauseContainerTask(uid, containerId);
        } else {
            throw new ForbiddenException("无法进行此操作");
        }
    }

    /**
     * 把容器从暂停状态恢复【WebSocket】
*/
    @GetMapping("/continue/{containerId}")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public void continueContainer(@RequestAttribute String uid, @PathVariable String containerId) {
        if (containerService.hasAllowOp(uid, containerId, ContainerOpEnum.CONTINUE)) {
            containerService.continueContainerTask(uid, containerId);
        } else {
            throw new ForbiddenException("无法进行此操作");
        }
    }

    /**
     * 停止容器【WebSocket】
*/
    @GetMapping("/stop/{containerId}")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public void stopContainer(@RequestAttribute String uid, @PathVariable String containerId) {
        if (containerService.hasAllowOp(uid, containerId, ContainerOpEnum.STOP)) {
            containerService.stopContainerTask(uid, containerId);
        } else {
            throw new ForbiddenException("无法进行此操作");
        }
    }

    /**
     * 强制停止容器【WebSocket】
*/
    @GetMapping("/kill/{containerId}")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public void killContainer(@RequestAttribute String uid, @PathVariable String containerId) {
        if (containerService.hasAllowOp(uid, containerId, ContainerOpEnum.KILL)) {
            containerService.killContainerTask(uid, containerId);
        } else {
            throw new ForbiddenException("无法进行此操作");
        }
    }

    /**
     * 重启容器【WebSocket】
*/
    @GetMapping("/restart/{containerId}")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public void restartContainer(@RequestAttribute String uid, @PathVariable String containerId) {
        if (containerService.hasAllowOp(uid, containerId, ContainerOpEnum.RESTART)) {
            containerService.restartContainerTask(uid, containerId);
        } else {
            throw new ForbiddenException("无法进行此操作");
        }
    }

    /**
     * 获取运行容器的内部状态
     * @return
     */
    @GetMapping("/top/{containerId}")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public TopResults topContainer(@RequestAttribute String uid, @PathVariable String containerId) {
        return containerService.topContainer(uid, containerId);
    }

    /**
     * 删除容器【WebSocket】
*/
    @DeleteMapping("/delete/{containerId}")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public void removeContainer(@PathVariable String containerId, @RequestAttribute String uid, HttpServletRequest request) {
        if (containerService.hasAllowOp(uid, containerId, ContainerOpEnum.DELETE)) {
            containerService.removeContainerTask(uid, containerId, request);
        } else {
            throw new ForbiddenException("不能进行删除操作");
        }
    }

    /**
     * 打包容器【WebSocket】
*/
    @PostMapping("/commit")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public void commitContainer(String containerId, String name,
                                    @RequestParam(defaultValue = "latest") String tag,
                                    @RequestAttribute String uid) {
        if (containerService.commitContainerCheck(containerId, name, tag, uid)) {
            containerService.commitContainerTask(containerId, name, tag, uid);
        } else {
            throw new ForbiddenException("不能进行打包操作");
        }
    }

    /**
     * 调用终端
     * @param containerId 容器ID
     * @return
     */
    @PostMapping("/terminal")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public Map<String, Object> showTerminal(@RequestAttribute String uid, String containerId,
                                            @RequestParam(defaultValue = "false") Boolean cursorBlink,
                                            @RequestParam(defaultValue = "100") Integer cols,
                                            @RequestParam(defaultValue = "50") Integer rows,
                                            @RequestParam(defaultValue = "100") Integer width,
                                            @RequestParam(defaultValue = "50") Integer height) {
        Container container = containerService.getById(containerId);
        if (container == null) {
            throw new ForbiddenException("容器不存在");
        }

        // 只有启动状态容器才能调用Terminal
        ContainerStatusEnum status = containerService.getStatus(containerId);
        if (status != ContainerStatusEnum.RUNNING) {
            throw new ForbiddenException("容器未启动");
        }

        // 鉴权
        if (!containerService.checkPermission(uid, containerId))
            throw new ForbiddenException("无权限");

        String url = "ws://" + serverIp + ":" + serverPort + "/ws/container/exec?width=" + width + "&height=" + height +
                "&ip=" + dockerAddress + "&port=" + dockerPort + "&containerId=" + containerId;

        Map<String, Object> map = new HashMap<>(16);
        map.put("cursorBlink", cursorBlink);
        map.put("cols", cols);
        map.put("rows", rows);
        map.put("url", url);
        return map;
    }

    /**
     * 同步容器状态
     * 普通用户同步本人容器，系统管理员同步所有容器
     * @return
     */
    @GetMapping("/sync")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public Map<String, Integer> sync(@RequestAttribute String uid) {
        String roleName = loginService.getRoleName(uid);

        if (RoleEnum.ROLE_USER.getMessage().equals(roleName)) {
            return containerService.syncStatus(uid);
        } else if (RoleEnum.ROLE_SYSTEM.getMessage().equals(roleName)) {
            return containerService.syncStatus(null);
        } else {
            throw new BadRequestException("不合法的用户身份");
        }
    }
}