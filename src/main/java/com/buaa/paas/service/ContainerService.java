package com.buaa.paas.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.buaa.paas.model.dto.ContainerDTO;
import com.buaa.paas.model.entity.Container;
import com.buaa.paas.model.enums.ContainerOpEnum;
import com.buaa.paas.model.enums.ContainerStatusEnum;
import com.spotify.docker.client.messages.TopResults;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 用户容器表 服务类
 * </p>
*/
public interface ContainerService extends IService<Container> {

    /**
     * 获取容器信息
     */
    ContainerDTO getById(String id);

    /**
     * 获取容器名
     */
    String getName(String id);
    /**
     * 是否允许容器操作
     * @param userId 用户ID
     * @param containerId 容器ID
     * @param containerOpEnum 目标操作
     * @return
     */
    Boolean hasAllowOp(String userId, String containerId, ContainerOpEnum containerOpEnum);

    /**
     * 创建容器前校验
     * @return
     */
    Boolean createContainerCheck(String userId, String imageId, Map<String, String> portMap, String projectId);

    /**
     * 创建容器任务
     */
    void createContainerTask(String userId, String imageId, String[] cmd, Map<String, String> portMap,
                             String containerName, String projectId, String[] env, String[]destination,
                             HttpServletRequest request);

    /**
     * 开启容器任务
     */
    void startContainerTask(String userId, String containerId);

    /**
     * 停止容器服务
     */
    void stopContainerTask(String userId, String containerId);

    /**
     * 强制停止容器
     */
    void killContainerTask(String userId, String containerId);

    /**
     * 移除容器任务
     */
    void removeContainerTask(String userId, String containerId, HttpServletRequest request);

    /**
     * 暂停容器
     */
    void pauseContainerTask(String userId, String containerId);

    /**
     * 重启容器
     */
    void restartContainerTask(String userId, String containerId);

    /**
     * 从暂停状态恢复
     */
    void continueContainerTask(String userId, String containerId);

    /**
     * 获取运行容器的内部状态
     * @return
     */
    TopResults topContainer(String userId, String containerId);

    Boolean checkPermission(String userId, String containerId);

    /**
     * 获取容器状态
     */
    ContainerStatusEnum getStatus(String containerId);

    /**
     * 获取用户所有镜像
     * @param name 容器名
     */
    Page<ContainerDTO> listContainerByUserId(String userId, String name, Integer status, Page<Container> page);

    /**
     * 根据状态获取容器列表
     */
    List<Container> listByStatus(ContainerStatusEnum statusEnum);

    /**
     * 同步容器状态
     * @param userId 用户ID，为空时同步所有
     */
    Map<String,Integer> syncStatus(String userId);

    /**
     * 修改数据库中容器状态
     */
    void changeStatus(String containerId);

    Boolean commitContainerCheck(String containerId, String name, String tag, String userId);

    /**
     * 打包容器
     */
    void commitContainerTask(String containerId, String name, String tag, String userId);
}