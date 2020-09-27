package com.buaa.paas.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buaa.paas.exception.ForbiddenException;
import com.buaa.paas.exception.NotFoundException;
import com.buaa.paas.model.entity.Container;
import com.buaa.paas.model.entity.Image;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.ContainerNotFoundException;
import com.spotify.docker.client.exceptions.DockerRequestException;
import com.spotify.docker.client.exceptions.DockerTimeoutException;
import com.spotify.docker.client.messages.*;
import com.buaa.paas.commons.activemq.MQProducer;
import com.buaa.paas.commons.activemq.Task;
import com.buaa.paas.commons.convert.UserContainerDTOConvert;
import com.buaa.paas.commons.util.*;
import com.buaa.paas.commons.util.jedis.JedisClient;
import com.buaa.paas.model.dto.ContainerDTO;
import com.buaa.paas.model.enums.*;
import com.buaa.paas.model.vo.ResultVO;
import com.buaa.paas.exception.CustomException;
import com.buaa.paas.mapper.ContainerMapper;
import com.buaa.paas.service.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.command.ActiveMQQueue;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.jms.Destination;
import javax.servlet.http.HttpServletRequest;
import java.util.*;


/**
 * <p>
 * 用户容器表 服务实现类
 * </p>
*/
@Service
@Slf4j
public class ContainerServiceImpl extends ServiceImpl<ContainerMapper, Container> implements ContainerService {
    @Autowired
    private PortService portService;
    @Autowired
    private AuthService loginService;
    @Autowired
    private ImageService imageService;
//    @Autowired
//    private SysNetworkService networkService;
//    @Autowired
//    private SysLogService // sysLogService;
//    @Autowired
//    private NoticeService noticeService;

//    @Autowired
//    private SysVolumesMapper sysVolumesMapper;
//    @Autowired
//    private UserProjectMapper projectMapper;
    @Autowired
    private ContainerMapper containerMapper;

    @Autowired
    private MQProducer mqProducer;
    @Autowired
    private DockerClient dockerClient;
    @Autowired
    private JedisClient jedisClient;

    @Autowired
    private UserContainerDTOConvert dtoConvert;

    @Value("${redis.container-name.key}")
    private String key;

    /**
     * 启动状态允许的操作
     */
    private final List<ContainerOpEnum> allowOpByRunning = Arrays.asList(ContainerOpEnum.PAUSE, ContainerOpEnum.STOP,
                                                                ContainerOpEnum.KILL, ContainerOpEnum.RESTART);
    /**
     * 暂停状态允许的操作
     */
    private final List<ContainerOpEnum> allowOpByPause = Arrays.asList(ContainerOpEnum.CONTINUE, ContainerOpEnum.STOP,
            ContainerOpEnum.KILL, ContainerOpEnum.RESTART);
    /**
     * 停止状态允许的操作
     */
    private final List<ContainerOpEnum> allowOpByStop = Arrays.asList(ContainerOpEnum.START, ContainerOpEnum.RESTART,
            ContainerOpEnum.DELETE);

    @Override
    public ContainerDTO getById(String id) {
        return dtoConvert.convert(containerMapper.selectById(id));
    }

    @Override
    public String getName(String id) {
        try {
            String name = jedisClient.hget(key, id);
            if(StringUtils.isNotBlank(name)) {
                return name;
            }
        } catch (Exception e) {
            log.error("缓存读取异常，异常位置：{}", "UserContainerServiceImpl.getName()");
        }

        String name = getById(id).getName();
        if(StringUtils.isBlank(name)) {
            return name;
        }

        try {
            jedisClient.hset(key,id,name);
        } catch (Exception e) {
            log.error("缓存存储异常，异常位置：{}", "UserContainerServiceImpl.getName()");
        }

        return name;
    }

    @Override
    public Boolean hasAllowOp(String userId, String containerId, ContainerOpEnum containerOpEnum) {
        // 1、鉴权
        if (!checkPermission(userId, containerId))
            return false;

        // 2、判断状态
        ContainerStatusEnum statusEnum = getStatus(containerId);
        if (statusEnum == ContainerStatusEnum.RUNNING) {
            // 运行：暂停、停止、强制停止、重启
            return allowOpByRunning.contains(containerOpEnum);
        } else if (statusEnum == ContainerStatusEnum.PAUSE) {
            // 暂停：恢复、停止、强制停止、重启
            return allowOpByPause.contains(containerOpEnum);
        } else if (statusEnum == ContainerStatusEnum.STOP) {
            // 停止：启动、重启、删除
            return allowOpByStop.contains(containerOpEnum);
        } else {
            return false;
        }
    }

    /**
     * API查询容器状态
     */
    @Override
    public ContainerStatusEnum getStatus(String containerId) {
        try {
            ContainerInfo info = dockerClient.inspectContainer(containerId);
            ContainerState state = info.state();

            ContainerStatusEnum statusEnum;
            if(state.running()) {
                if(state.paused()) {
                    statusEnum = ContainerStatusEnum.PAUSE;
                } else {
                    statusEnum = ContainerStatusEnum.RUNNING;
                }
            } else {
                statusEnum =  ContainerStatusEnum.STOP;
            }

            // 如果查询的状态和数据库不一致，更新数据库状态
            ContainerDTO containerDTO = getById(containerId);
            if(containerDTO != null) {
                if(statusEnum.getCode() != containerDTO.getStatus()) {
                    containerDTO.setStatus(statusEnum.getCode());
                    containerMapper.updateById(containerDTO);
                }
            }

            return statusEnum;
        } catch (ContainerNotFoundException e) {
            return ContainerStatusEnum.REMOVE;
        } catch (DockerTimeoutException te) {
            log.error("获取容器状态超时，异常位置：{}，容器ID：{}",
                    "UserContainerServiceImpl.getStatus()", containerId);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("获取容器状态出现异常，异常位置：{}，错误栈：{}",
                    "UserContainerServiceImpl.getStatus()",HttpClientUtils.getStackTraceAsString(e));
        }
        return null;
    }

    @Override
    public Boolean checkPermission(String userId, String containerId) {
        // 1、鉴权
        String roleName = loginService.getRoleName(userId);
        // 1.1、角色无效
        if(StringUtils.isBlank(roleName)) {
            return false;
        }
        // 1.2、越权访问
        if(RoleEnum.ROLE_USER.getMessage().equals(roleName)) {
            ContainerDTO containerDTO = getById(containerId);
            if(containerDTO == null) {
                return false;
            }

            if(!containerDTO.getUserId().equals(userId)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public Boolean createContainerCheck(String userId, String imageId,
                                        Map<String, String> portMap, String projectId) {
        // 1、Project鉴权
//        Boolean b = projectMapper.hasBelong(projectId, userId);
//        if(!b) {
//            return ResultVOUtils.error(ResultEnum.PERMISSION_ERROR);
//        }

        // 2、校验Image
        Image image = imageService.getById(imageId);
        if(image == null) {
            return false;
        }
        if(!imageService.hasAuthImage(userId, image)) {
            return false;
        }

        // 获取暴露接口
        List<String> exportPorts;
        try {
            exportPorts = imageService.listExportPorts(imageId, userId);
        } catch (Exception e) {
            return false;
        }

        // 3、校验输入的端口
        if (!checkPorts(exportPorts, portMap)) {
            return false;
        }

        return true;
    }

    @Async("taskExecutor")
    @Transactional(rollbackFor = CustomException.class)
    @Override
    public void createContainerTask(String userId, String imageId, String[] cmd, Map<String, String> portMap,
                                    String containerName, String projectId, String[] env, String[] destination,
                                    HttpServletRequest request) {
        Image image = imageService.getById(imageId);
        Container uc = new Container();
        HostConfig hostConfig;
        ContainerConfig.Builder builder = ContainerConfig.builder();

        // 1、设置暴露端口
        if(portMap != null) {
            // 宿主机端口与暴露端口绑定
            Set<String> realExportPorts = new HashSet<>();
            Map<String, List<PortBinding>> portBindings = new HashMap<>(16);

            for(Map.Entry<String, String> entry : portMap.entrySet()) {
                String k = entry.getKey();
                int v = Integer.parseInt(entry.getValue());
                realExportPorts.add(k);
                // 捆绑端口
                List<PortBinding> hostPorts = new ArrayList<>();
                // 分配主机端口，如果用户输入端口被占用，随机分配
                int hostPort = portService.hasUse(v) ? portService.randomPort() : v;
                hostPorts.add(PortBinding.of("0.0.0.0", hostPort));
                portBindings.put(k, hostPorts);
            }

            uc.setPort(JsonUtils.objectToJson(portBindings));

            builder.exposedPorts(realExportPorts);

            hostConfig = HostConfig.builder()
                    .portBindings(portBindings)
                    .build();
        } else {
            hostConfig = HostConfig.builder().build();
        }

        // 2、构建ContainerConfig
        builder.hostConfig(hostConfig);
        builder.image(image.getFullName());
        builder.tty(true);
        if(CollectionUtils.isNotArrayEmpty(cmd)) {
            builder.cmd(cmd);
            uc.setCommand(Arrays.toString(cmd));
        }
//        if(CollectionUtils.isNotArrayEmpty(destination)) {
//            builder.volumes(destination);
//        }
        if(CollectionUtils.isNotArrayEmpty(env)) {
            builder.env(env);
            uc.setEnv(Arrays.toString(env));
        }
        ContainerConfig containerConfig = builder.build();

        try {
            ContainerCreation creation = dockerClient.createContainer(containerConfig);

            uc.setId(creation.id());
            // 仅存在于数据库，不代表实际容器名
            uc.setName(containerName);
            uc.setProjectId(projectId);
            uc.setUserId(userId);
            uc.setImage(image.getFullName());

//            if(CollectionUtils.isNotArrayEmpty(destination)) {
                // 为数据库中的sysvolumes插入
//                ImmutableList<ContainerMount> info = dockerClient.inspectContainer(creation.id()).mounts();
//                if(CollectionUtils.isListNotEmpty(info)) {
//                    for(ContainerMount mount : info) {
//                        SysVolume sysVolume = new SysVolume();
//                        sysVolume.setObjId(creation.id());
//                        sysVolume.setDestination(mount.destination());
//                        sysVolume.setName(mount.name());
//                        sysVolume.setSource(mount.source());
//                        sysVolume.setType(VolumeTypeEnum.CONTAINER.getCode());
//                        sysVolumesMapper.insert(sysVolume);
//                    }
//                }
//            }

            // 3、设置状态
            ContainerStatusEnum status = getStatus(creation.id());
            if(status == null) {
                throw new CustomException(ResultEnum.DOCKER_EXCEPTION.getCode(), "读取容器状态异常");
            }
            uc.setStatus(status.getCode());
            uc.setCreateDate(new Date());

            containerMapper.insert(uc);

            // 4、保存网络信息
//            networkService.syncByContainerId(uc.getId());

            // 5、写入日志
            // sysLogService.saveLog(request, SysLogTypeEnum.CREATE_CONTAINER);
            // projectLogService.saveSuccessLog(projectId,uc.getId(),ProjectLogTypeEnum.CREATE_CONTAINER);

            // 发送通知
            // List<String> // // receiverList = new ArrayList<>();
            // // receiverList.add(userId);
            // noticeService.sendUserTask("创建容器", "创建容器【" + containerName + "】成功", 2, false, // // receiverList, null);

            sendMQ(userId, null, ResultVOUtils.successWithMsg("容器【"+containerName+"】创建成功"));
        } catch (DockerRequestException requestException){
            log.error("创建容器出现异常，错误位置：{}，错误原因：{}",
                    "UserContainerServiceImpl.createContainerTask()", requestException.getMessage());
            sendMQ(userId, null, ResultVOUtils.error(
                    ResultEnum.CREATE_CONTAINER_ERROR.getCode(),HttpClientUtils.getErrorMessage(requestException.getMessage())));
        } catch (Exception e) {
            log.error("创建容器出现异常，错误位置：{}，错误栈：{}",
                    "UserContainerServiceImpl.createContainerTask()", HttpClientUtils.getStackTraceAsString(e));

            // 写入日志
            // sysLogService.saveLog(request, SysLogTypeEnum.CREATE_CONTAINER, e);
            // projectLogService.saveErrorLog(projectId,uc.getId(),ProjectLogTypeEnum.CREATE_CONTAINER_ERROR,ResultEnum.DOCKER_EXCEPTION);

            // 发送通知
            // List<String> // // receiverList = new ArrayList<>();
            // // receiverList.add(userId);
            // noticeService.sendUserTask("创建容器","创建容器【"+containerName+"】失败,Docker异常", 2, false, // // receiverList, null);

            sendMQ(userId, null, ResultVOUtils.error(ResultEnum.DOCKER_EXCEPTION));
        }
    }

    /**
     * 开启容器任务
     */
    @Async("taskExecutor")
    @Transactional(rollbackFor = CustomException.class)
    @Override
    public void startContainerTask(String userId, String containerId) {
        try {
            dockerClient.startContainer(containerId);
            changeStatus(containerId);

            // 写入日志
            // projectLogService.saveSuccessLog(getProjectId(containerId),containerId,ProjectLogTypeEnum.START_CONTAINER);

            // 发送成功消息
            sendMQ(userId, containerId, ResultVOUtils.successWithMsg("容器"+getName(containerId)+"启动成功"));
        } catch (Exception e) {
            log.error("开启容器出现异常，异常位置：{}，错误栈：{}",
                    "UserContainerServiceImpl.startContainerTask()",HttpClientUtils.getStackTraceAsString(e));
            // 写入日志
            // projectLogService.saveErrorLog(getProjectId(containerId),containerId,ProjectLogTypeEnum.START_CONTAINER_ERROR,ResultEnum.DOCKER_EXCEPTION);

            // 发送异常消息
            sendMQ(userId, containerId, ResultVOUtils.error(ResultEnum.CONTAINER_START_ERROR));
        }
    }

    @Async("taskExecutor")
    @Transactional(rollbackFor = CustomException.class)
    @Override
    public void stopContainerTask(String userId, String containerId) {
        try {
            dockerClient.stopContainer(containerId, 5);
            changeStatus(containerId);
            // 写入日志
            // projectLogService.saveSuccessLog(getProjectId(containerId), containerId, ProjectLogTypeEnum.STOP_CONTAINER);

            // 发送成功消息
            sendMQ(userId, containerId, ResultVOUtils.successWithMsg("容器" + getName(containerId) + "关闭成功"));
        } catch (Exception e) {
            log.error("停止容器出现异常，异常位置：{}，错误栈：{}","UserContainerServiceImpl.stopContainerTask()",HttpClientUtils.getStackTraceAsString(e));
            // 写入日志
            // projectLogService.saveErrorLog(getProjectId(containerId), containerId, ProjectLogTypeEnum.STOP_CONTAINER_ERROR,ResultEnum.DOCKER_EXCEPTION);
            // 发送异常消息
            sendMQ(userId, containerId, ResultVOUtils.error(ResultEnum.DOCKER_EXCEPTION));
        }
    }

    @Async("taskExecutor")
    @Transactional(rollbackFor = CustomException.class)
    @Override
    public void killContainerTask(String userId, String containerId) {
        try {
            dockerClient.killContainer(containerId);
            changeStatus(containerId);
            // 写入日志
            // projectLogService.saveSuccessLog(getProjectId(containerId), containerId, ProjectLogTypeEnum.KILL_CONTAINER);
            // 发送成功消息
            sendMQ(userId, containerId, ResultVOUtils.successWithMsg("容器" + getName(containerId) + "强制关闭成功"));
        } catch (Exception e) {
            log.error("强制停止容器出现异常，异常位置：{}，错误栈：{}", "UserContainerServiceImpl.killContainerTask()", HttpClientUtils.getStackTraceAsString(e));
            // 写入日志
            // projectLogService.saveErrorLog(getProjectId(containerId), containerId, ProjectLogTypeEnum.KILL_CONTAINER_ERROR, ResultEnum.DOCKER_EXCEPTION);
            // 发送异常消息
            sendMQ(userId, containerId, ResultVOUtils.error(ResultEnum.DOCKER_EXCEPTION));
        }
    }

    @Async("taskExecutor")
    @Transactional(rollbackFor = CustomException.class)
    @Override
    public void removeContainerTask(String userId, String containerId, HttpServletRequest request) {
        try {
            dockerClient.removeContainer(containerId);
            // 删除数据
            String name = getName(containerId);
            containerMapper.deleteById(containerId);
            // 删除数据卷
//            sysVolumesMapper.deleteByObjId(containerId);
            // 清理缓存
            cleanCache(containerId);
            // 写入日志
            // sysLogService.saveLog(request, SysLogTypeEnum.DELETE_CONTAINER);
            // projectLogService.saveSuccessLog(getProjectId(containerId), containerId, ProjectLogTypeEnum.DELETE_CONTAINER);
            // 发送成功消息
            sendMQ(userId, containerId, ResultVOUtils.successWithMsg("容器" + name + "移除成功"));
        } catch (Exception e) {
            log.error("删除容器出现异常，异常位置：{}，错误栈：{}",
                    "UserContainerServiceImpl.removeContainerTask()", HttpClientUtils.getStackTraceAsString(e));
            // 写入日志
            // sysLogService.saveLog(request, SysLogTypeEnum.DELETE_CONTAINER, e);
            // projectLogService.saveErrorLog(getProjectId(containerId), containerId, ProjectLogTypeEnum.DELETE_CONTAINER_ERROR,ResultEnum.DOCKER_EXCEPTION);
            // 发送异常消息
            sendMQ(userId, containerId, ResultVOUtils.error(ResultEnum.DOCKER_EXCEPTION));
        }
    }

    @Async("taskExecutor")
    @Transactional(rollbackFor = CustomException.class)
    @Override
    public void pauseContainerTask(String userId, String containerId) {
        try {
            dockerClient.pauseContainer(containerId);
            changeStatus(containerId);
            // 写入日志
            // projectLogService.saveSuccessLog(getProjectId(containerId), containerId, ProjectLogTypeEnum.PAUSE_CONTAINER);

            // 发送成功消息
            sendMQ(userId, containerId, ResultVOUtils.successWithMsg("容器" + getName(containerId) + "暂停成功"));
        } catch (Exception e) {
            e.printStackTrace();
            log.error("暂停容器出现异常，异常位置：{}，错误栈：{}","UserContainerServiceImpl.pauseContainerTask()",HttpClientUtils.getStackTraceAsString(e));
            // 写入日志
            // projectLogService.saveErrorLog(getProjectId(containerId), containerId, ProjectLogTypeEnum.PAUSE_CONTAINER_ERROR,ResultEnum.DOCKER_EXCEPTION);
            // 发送异常消息
            sendMQ(userId, containerId, ResultVOUtils.error(ResultEnum.DOCKER_EXCEPTION));
        }
    }

    @Async("taskExecutor")
    @Transactional(rollbackFor = CustomException.class)
    @Override
    public void restartContainerTask(String userId, String containerId) {
        try {
            dockerClient.restartContainer(containerId);
            changeStatus(containerId);
            // 写入日志
            // projectLogService.saveSuccessLog(getProjectId(containerId), containerId, ProjectLogTypeEnum.RESTART_CONTAINER);

            // 发送成功消息
            sendMQ(userId, containerId, ResultVOUtils.successWithMsg("容器" + getName(containerId) + "重启成功"));
        } catch (Exception e) {
            e.printStackTrace();
            log.error("重启容器出现异常，异常位置：{}，错误栈：{}","UserContainerServiceImpl.restartContainerTask()",HttpClientUtils.getStackTraceAsString(e));
            // 写入日志
            // projectLogService.saveErrorLog(getProjectId(containerId), containerId, ProjectLogTypeEnum.RESTART_CONTAINER_ERROR,ResultEnum.DOCKER_EXCEPTION);

            // 发送异常消息
            sendMQ(userId, containerId, ResultVOUtils.error(ResultEnum.DOCKER_EXCEPTION));
        }
    }

    @Async("taskExecutor")
    @Transactional(rollbackFor = CustomException.class)
    @Override
    public void continueContainerTask(String userId, String containerId) {
        try {
            dockerClient.unpauseContainer(containerId);
            changeStatus(containerId);
            // 写入日志
            // projectLogService.saveSuccessLog(getProjectId(containerId), containerId, ProjectLogTypeEnum.CONTINUE_CONTAINER);
            // 发送成功消息
            sendMQ(userId, containerId, ResultVOUtils.successWithMsg("容器" + getName(containerId) + "恢复成功"));
        } catch (Exception e) {
            log.error("继续容器出现异常，异常位置：{}，错误栈：{}","UserContainerServiceImpl.continueContainerTask()",HttpClientUtils.getStackTraceAsString(e));
            // 写入日志
            // projectLogService.saveErrorLog(getProjectId(containerId), containerId, ProjectLogTypeEnum.CONTINUE_CONTAINER,ResultEnum.DOCKER_EXCEPTION);

            // 发送异常消息
            sendMQ(userId, containerId, ResultVOUtils.error(ResultEnum.DOCKER_EXCEPTION));
        }
    }

    @Override
    public TopResults topContainer(String userId, String containerId) {
        // 鉴权
        if (!checkPermission(userId, containerId))
            throw new ForbiddenException("无权限查看");

        // 只有启动状态才能查看进程
        ContainerStatusEnum status = getStatus(containerId);
        if(status != ContainerStatusEnum.RUNNING) {
            throw new ForbiddenException("容器未启动");
        }

        try {
            return dockerClient.topContainer(containerId);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("查看容器进程出现异常，异常位置：{}，错误栈：{}","UserContainerServiceImpl.continueContainer()",HttpClientUtils.getStackTraceAsString(e));
            throw new ForbiddenException("Docker异常");
        }
    }

    @Override
    public Page<ContainerDTO> listContainerByUserId(String userId, String name, Integer status, Page<Container> page) {
        List<Container> containers = containerMapper.listContainerByUserIdAndNameAndStatus(page, userId, name, status);

        Page<ContainerDTO> page1 = new Page<>();
        BeanUtils.copyProperties(page, page1);
        return page1.setRecords(dtoConvert.convert(containers));
    }

    @Override
    public List<Container> listByStatus(ContainerStatusEnum statusEnum) {
        return containerMapper.selectList(new QueryWrapper<Container>().eq("status", statusEnum.getCode()));
    }

    /**
     * 修改数据库中容器状态
     */
    @Override
    public void changeStatus(String containerId) {
        ContainerStatusEnum statusEnum = getStatus(containerId);
        if(statusEnum == null) {
            throw new NotFoundException("未找到此容器状态");
        }

        if(statusEnum == ContainerStatusEnum.REMOVE) {
            containerMapper.deleteById(containerId);
            return;
        }

        ContainerDTO containerDTO = getById(containerId);
        if(containerDTO == null) {
            throw new NotFoundException("未找到此容器");
        }

        if(containerDTO.getStatus() != statusEnum.getCode()) {
            containerDTO.setStatus(statusEnum.getCode());
            containerMapper.updateById(containerDTO);
        }
    }

    @Override
    public Boolean commitContainerCheck(String containerId, String name, String tag, String userId) {
        // 鉴权
        if (!checkPermission(userId, containerId))
            return false;

        String fullName = "local/" + userId + "/" + name + ":" + tag;
        // 判断是否存在
        return imageService.getByFullName(fullName) == null;
    }


    @Async("taskExecutor")
    @Transactional(rollbackFor = CustomException.class)
    @Override
    public void commitContainerTask(String containerId, String name, String tag, String userId) {
        try {
            ContainerConfig config = ContainerConfig.builder().build();

            String repo = "local/" + userId + "/" + name;
            String fullName = repo + ":" + tag;

            // 2、导入镜像
            dockerClient.commitContainer(containerId, repo, tag, config, "", loginService.getById(userId).getUsername());

            // 3、保存数据库
            if(!imageService.saveImage(fullName)) {
                throw new CustomException(ResultEnum.OTHER_ERROR.getCode(), "保存镜像数据错误");
            }
            // 发送成功消息
            sendMQ(userId, containerId, ResultVOUtils.successWithMsg("镜像" + name + "打包成功"));
        } catch (Exception e) {
            log.error("打包镜像错误，错误位置：{}，错误栈：{}",
                    "UserContainerServiceImpl.commitContainer", HttpClientUtils.getStackTraceAsString(e));
            // 发送异常消息
            sendMQ(userId, containerId, ResultVOUtils.error(ResultEnum.IMAGE_COMMIT_ERROR));
        }
    }

    @Override
    @Transactional(rollbackFor = CustomException.class)
    public Map<String, Integer> syncStatus(String userId) {
        // 读取数据库容器列表
        List<Container> containers;

        //为空时同步所有
        if(StringUtils.isBlank(userId)) {
            containers = containerMapper.selectList(new QueryWrapper<>());
        } else{
            containers = containerMapper.selectList(new QueryWrapper<Container>().eq("user_id",userId));
        }

        int successCount = 0, errorCount = 0;
        for(Container container : containers) {
            try {
                changeStatus(container.getId());
                successCount++;
            } catch (Exception e) {
                errorCount++;
            }
        }

        Map<String, Integer> map =new HashMap<>(16);
        map.put("success", successCount);
        map.put("error", errorCount);
        return map;
    }

    /**
     * 检查端口号
     * （1）用户输入端口号是否合法 & 可用
     * （2）容器暴露端口是否都设置了
     */
    private boolean checkPorts(List<String> exportPorts, Map<String,String> map) {
        // 校验NULL
        if(CollectionUtils.isListEmpty(exportPorts) && map == null) {
            return true;
        }
        if(CollectionUtils.isListNotEmpty(exportPorts) && map == null) {
            return false;
        }

        /*
         * 如果暴露接口非空
         * （1）判断暴露接口是否都设置
         * （2）判断接口是否合法
         * 如果暴露接口空
         * （1）判断接口是否合法
         */
        if(CollectionUtils.isListNotEmpty(exportPorts)) {
            for(String port : exportPorts) {
                if(map.get(port) == null) {
                    return false;
                }
            }
        }
        return hasPortIllegal(map);
    }

    private boolean hasPortIllegal(Map<String,String> map) {
        for (Map.Entry<String, String> entry : map.entrySet()) {
            int value = Integer.parseInt(entry.getValue());

            // 判断数字
            try {
                Integer.parseInt(entry.getKey());
            } catch (Exception e) {
                return false;
            }

            // value允许端口范围：[10000 ~ 65535)
            if(value < 10000 || value > 65535) {
                return false;
            }
        }
        return true;
    }

    /**
     * 发送容器消息
     */
    private void sendMQ(String userId, String containerId, ResultVO resultVO) {
        Destination destination = new ActiveMQQueue("MQ_QUEUE_CONTAINER");
        Task task = new Task();

        Map<String, Object> data = new HashMap<>(16);
        data.put("type", WebSocketTypeEnum.CONTAINER.getCode());
        data.put("containerId", containerId);
        resultVO.setData(data);

        Map<String,String> map = new HashMap<>(16);
        map.put("uid",userId);
        map.put("data", JsonUtils.objectToJson(resultVO));
        task.setData(map);

        mqProducer.send(destination, JsonUtils.objectToJson(task));
    }

    private void cleanCache(String id) {
        try {
            jedisClient.hdel(key, id);
        } catch (Exception e) {
            log.error("删除缓存出现异常，异常位置：{}", "UserContainerServiceImpl.cleanCache");
        }
    }
}