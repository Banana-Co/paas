package com.buaa.paas.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.buaa.paas.model.dto.ImageDTO;
import com.buaa.paas.model.entity.Image;
import com.spotify.docker.client.messages.ImageHistory;
import com.spotify.docker.client.messages.ImageInfo;

import javax.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <p>
 *  镜像服务类
 * </p>
*/
public interface ImageService extends IService<Image> {
    Image getById(String id);

    /**
     * 获取本地公共镜像
     */
    Page<ImageDTO> listLocalPublicImage(String name, Page<ImageDTO> page);

    /**
     * 获取本地用户镜像
     * @param filterOpen 是否只显示公开镜像
     */
    Page<ImageDTO> listLocalUserImage(String name, boolean filterOpen, String userId, Page<ImageDTO> page);

    /**
     * 根据完整名获取镜像
     */
    Image getByFullName(String fullName);

    /**
     * 查询镜像详细信息
     * @return
     */
    ImageInfo inspectImage(String id, String userId);

    /**
     * 同步本地镜像到数据库
     * @return
     */
    Map sync();

    /**
     * 删除镜像
     */
    void removeImage(String id, String userId, HttpServletRequest request);


    /**
     *  查看History
     * @return
     */
    List<ImageHistory> getHistory(String id, String uid);

    /**
     * 公开/关闭私有镜像
     * 仅所有者本人操作
     */
    void changOpenImage(String id, String uid, boolean code);

    /**
     * 获取一个镜像的所有暴露接口
     * @return
     */
    ArrayList<String> listExportPorts(String imageId, String userId);

    /**
     * 导入镜像
     * @param stream 文件流对象
     * @param fullName 镜像完整名
     */
    void importImageTask(InputStream stream, String fullName, String uid, HttpServletRequest request);

    /**
     * 清理缓存
     * 根据ID或完整名清理
     */
    void cleanCache(String id, String fullName);

    /**
     * 是否有权限访问镜像
     */
    Boolean hasAuthImage(String userId, Image image);

    /**
     * 获取当前用户的所有镜像
     */
    Page<Image> selfImage(String userId, Page<Image> page);

    /**
     * 清理无效镜像
     * @return
     */
    Map cleanImage();

    /**
     * 根据完整名保存数据
     */
    boolean saveImage(String fullName);
}