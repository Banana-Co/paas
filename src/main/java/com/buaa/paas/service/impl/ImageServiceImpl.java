package com.buaa.paas.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buaa.paas.exception.*;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.ConflictException;
import com.spotify.docker.client.exceptions.DockerRequestException;
import com.spotify.docker.client.exceptions.DockerTimeoutException;
import com.spotify.docker.client.messages.*;
import com.buaa.paas.commons.activemq.MQProducer;
import com.buaa.paas.commons.activemq.Task;
import com.buaa.paas.commons.util.*;
import com.buaa.paas.commons.util.jedis.JedisClient;
import com.buaa.paas.model.dto.ImageDTO;
import com.buaa.paas.model.entity.Image;
import com.buaa.paas.model.entity.Auth;
import com.buaa.paas.model.enums.*;
import com.buaa.paas.model.vo.ResultVO;
import com.buaa.paas.mapper.ImageMapper;
import com.buaa.paas.service.ImageService;
import com.buaa.paas.service.AuthService;
import com.spotify.docker.client.shaded.com.google.common.collect.ImmutableList;
import com.spotify.docker.client.shaded.com.google.common.collect.ImmutableSet;
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
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * Image服务实现类
 * </p>
*/
@Service
@Slf4j
public class ImageServiceImpl extends ServiceImpl<ImageMapper, Image> implements ImageService {
    @Autowired
    private ImageMapper imageMapper;
    @Autowired
    private AuthService loginService;
    @Autowired
    private DockerClient dockerClient;
    @Autowired
    private JedisClient jedisClient;
    @Autowired
    private MQProducer mqProducer;
    // @Autowired
    // private SysLogService sysLogService;
    // @Autowired
    // private NoticeService noticeService;

    @Value("${docker.server.url}")
    private String serverUrl;

    @Value("${redis.local-image.key}")
    private String key;
    private final String ID_PREFIX = "ID:";
    private final String FULL_NAME_PREFIX = "FULL_NAME:";

    @Override
    public Page<ImageDTO> listLocalPublicImage(String name, Page<ImageDTO> page) {
        return page.setRecords(imageMapper.listLocalPublicImage(page, name));
    }

    @Override
    public Page<ImageDTO> listLocalUserImage(String name, boolean filterOpen, String userId, Page<ImageDTO> page) {
        List<ImageDTO> images;
        if(filterOpen) {
            List<Image> imageList = imageMapper.selectList(new QueryWrapper<Image>()
                    .eq("type", ImageTypeEnum.LOCAL_USER_IMAGE.getCode())
                    .and(wrapper -> wrapper.eq("user_id", userId).or().eq("has_open", true)));
            images = sysImage2DTO(imageList);

        } else {
            images = imageMapper.listLocalUserImage(page, name);
        }

        return page.setRecords(images);
    }

    @Override
    public Image getById(String id) {
        String field = ID_PREFIX + id;

        try {
            String json = jedisClient.hget(key, field);
            if(StringUtils.isNotBlank(json)) {
                return JsonUtils.jsonToObject(json, Image.class);
            }
        } catch (Exception e) {
            log.error("缓存读取异常，异常位置：{}", "SysImageServiceImpl.getById()");
        }

        Image image = imageMapper.selectById(id);
        if(image == null) {
            return null;
        }

        try {
            String json = JsonUtils.objectToJson(image);
            jedisClient.hset(key, field, json);
        } catch (Exception e) {
            log.error("缓存存储异常，异常位置：{}", "SysImageServiceImpl.getById()");
        }

        return image;
    }

    @Override
    public Image getByFullName(String fullName) {
        String field = FULL_NAME_PREFIX + fullName;

        try {
            String json = jedisClient.hget(key, field);
            if(StringUtils.isNotBlank(json)) {
                return JsonUtils.jsonToObject(json, Image.class);
            }
        } catch (Exception e) {
            log.error("缓存读取异常，异常位置：{}", "SysImageServiceImpl.getByFullName()");
        }

        List<Image> images = imageMapper.selectList(new QueryWrapper<Image>().eq("full_name", fullName));
        Image image = CollectionUtils.getListFirst(images);
        if(image == null) {
            return null;
        }

        try {
            String json = JsonUtils.objectToJson(image);
            jedisClient.hset(key, field, json);
        } catch (Exception e) {
            log.error("缓存存储异常，异常位置：{}", "SysImageServiceImpl.getByFullName()");
        }

        return image;
    }

    /**
     * 查询镜像详细信息
     */
    @Override
    public ImageInfo inspectImage(String id, String userId) {
        // 1、校验参数
        if(StringUtils.isBlank(id)) {
            throw new BadRequestException("id为空");
        }

        // 2、查询数据库
        Image image = getById(id);
        if(image == null) {
            throw new NotFoundException("无此id的镜像");
        }
        // 3、判断是否有权限访问
        if(!hasAuthImage(userId, image)) {
            throw new ForbiddenException("无访问此镜像的权限");
        }

        // 4、查询信息
        try {
            String fullName = image.getFullName();

            return dockerClient.inspectImage(fullName);
        }catch (Exception e) {
            log.error("Docker查询详情异常，错误位置：{}，错误栈：{}",
                    "SysImageServiceImpl.inspectImage", HttpClientUtils.getStackTraceAsString(e));
            throw new InternalServerErrorException(String.format("Docker 查询异常: %s", e.getMessage()));
        }
    }

    /**
     * 同步本地镜像到数据库
     * @return
     */
    @Transactional(rollbackFor = CustomException.class)
    @Override
    public Map sync() {
        try {
            // 1、获取数据库中所有镜像
            List<Image> dbImages = imageMapper.selectList(new QueryWrapper<>());
            // 2、获取本地所有镜像
            List<com.spotify.docker.client.messages.Image> tmps = dockerClient.listImages(DockerClient.ListImagesParam.digests());

            int deleteCount = 0,addCount = 0,errorCount=0;
            boolean[] dbFlag = new boolean[dbImages.size()];
            Arrays.fill(dbFlag,false);

            // 3、遍历本地镜像
            for (com.spotify.docker.client.messages.Image image : tmps) {
                // 读取所有Tag
                ImmutableList<String> list = image.repoTags();
                if (list != null) {
                    for (String tag : list) {
                        // 判断tag是否存在
                        boolean flag = false;
                        for (int j = 0; j < dbImages.size(); j++) {
                            // 跳过比较过的
                            if (dbFlag[j]) {
                                continue;
                            }
                            // 比较相等
                            if (tag.equals(dbImages.get(j).getFullName())) {
                                flag = true;
                                dbFlag[j] = true;
                                break;
                            }
                        }

                        // 如果本地不存在，添加到本地
                        if (!flag) {
                            Image sysImage = imageToSysImage(image, tag);
                            if (sysImage == null) {
                                errorCount++;
                            } else {
                                addCount++;
                                imageMapper.insert(sysImage);
                            }
                        }
                    }
                }
            }

            // 删除失效的数据
            for(int i=0; i<dbFlag.length;i++) {
                if(!dbFlag[i]) {
                    deleteCount++;
                    Image image = dbImages.get(i);
                    imageMapper.deleteById(image);
                    // 更新缓存
                    cleanCache(image.getId(), image.getFullName());
                }
            }

            // 准备结果
            Map<String, Integer> map = new HashMap<>(16);
            map.put("delete", deleteCount);
            map.put("add", addCount);
            map.put("error", errorCount);

            return map;
        } catch (DockerTimeoutException te) {
            log.error("同步镜像超时，错误位置：{}","SysImageServiceImpl.sync");
            throw new InternalServerErrorException("Docker同步镜像超时");
        }  catch (Exception e) {
            log.error("Docker同步镜像异常，错误位置：{},错误栈：{}",
                    "SysImageServiceImpl.sync", HttpClientUtils.getStackTraceAsString(e));
            throw new InternalServerErrorException("Docker同步镜像异常");
        }
    }

    /**
     * 删除镜像
     * （1）普通用户只能删除自己上传的镜像
     * （2）管理员可以删除任意镜像
     * （3）如果有任意容器正在使用，无法删除，请使用强制删除的方法
     */
    @Override
    @Transactional(rollbackFor = CustomException.class)
    public void removeImage(String id, String userId, HttpServletRequest request) {
        String roleName = loginService.getRoleName(userId);
        Image image = getById(id);
        if(image == null) {
            throw new NotFoundException("镜像不存在");
        }

        if(RoleEnum.ROLE_USER.getMessage().equals(roleName)) {
            // 普通用户无法删除公有镜像
            if(ImageTypeEnum.LOCAL_PUBLIC_IMAGE.getCode() == image.getType()) {
                throw new ForbiddenException("普通用户无法删除公有镜像");
            }
            // 普通用户无法删除他人镜像
            if(!userId.equals(image.getUserId())) {
                throw new ForbiddenException("普通用户无法删除他人镜像");
            }
        }

        try {
            // 删除镜像
            dockerClient.removeImage(image.getFullName());
            // 删除记录
            imageMapper.deleteById(image);
            // 清除缓存
            cleanCache(image.getId(), image.getFullName());

        } catch (DockerRequestException requestException){
            String message = HttpClientUtils.getErrorMessage(requestException.getMessage());
            log.error("删除镜像异常，错误位置：{}，错误信息：{}",
                    "SysImageServiceImpl.removeImage", message);
            throw new InternalServerErrorException("Docker删除镜像异常");
        }catch (ConflictException e){
            throw new InternalServerErrorException("Docker删除镜像异常");
        }catch (Exception e) {
            log.error("Docker删除镜像异常，错误位置：{},错误栈：{}"
                    ,"SysImageServiceImpl.removeImage",HttpClientUtils.getStackTraceAsString(e));
            throw new InternalServerErrorException("Docker删除镜像异常");
        }
    }

    /**
     * 导入镜像任务
     */
    @Async("taskExecutor")
    @Transactional(rollbackFor = CustomException.class)
    @Override
    public void importImageTask(InputStream stream, String fullName, String uid, HttpServletRequest request) {
        // 导入镜像
        try {
            dockerClient.create(fullName,stream);
            // 获取镜像的信息
            List<com.spotify.docker.client.messages.Image> list = dockerClient.listImages(DockerClient.ListImagesParam.byName(fullName));
            com.spotify.docker.client.messages.Image image = CollectionUtils.getListFirst(list);

            Image sysImage = imageToSysImage(image, image.repoTags().get(0));
            // 插入数据
            imageMapper.insert(sysImage);
            // 写入日志
            // sysLogService.saveLog(request, SysLogTypeEnum.IMPORT_IMAGE);
            // 发送通知
            // List<String> receiverList = new ArrayList<>();
            // receiverList.add(uid);
            // noticeService.sendUserTask("导入镜像", "导入镜像【" + fullName + "】成功", 4, false, receiverList, null);
            //sendMQ(uid, null, ResultVOUtils.successWithMsg("镜像导入成功"));
        } catch (DockerRequestException requestException){
            log.error("导入镜像失败，错误位置：{}，错误原因：{}",
                    "SysImageServiceImpl.importImageTask()", requestException.getMessage());
            //sendMQ(uid, null, ResultVOUtils.error(ResultEnum.SERVICE_CREATE_ERROR.getCode(),HttpClientUtils.getErrorMessage(requestException.getMessage())));
        }catch (Exception e) {
            log.error("导入镜像失败，错误位置：{}，镜像名：{}，错误栈：{}",
                    "SysImageServiceImpl.importImageTask()", fullName, HttpClientUtils.getStackTraceAsString(e));
            // 写入日志
            // sysLogService.saveLog(request, SysLogTypeEnum.IMPORT_IMAGE, e);

            // 发送通知
            // List<String> receiverList = new ArrayList<>();
            // receiverList.add(uid);
            // noticeService.sendUserTask("导入镜像", "导入镜像【" + fullName + "】失败,Docker导入失败", 4, false, receiverList, null);

            //sendMQ(uid, null, ResultVOUtils.error(ResultEnum.IMPORT_ERROR));
        } finally {
            if(stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {

                }
            }
        }
    }

    /**
     * 查看History
     * @return
     */
    @Override
    public List<ImageHistory> getHistory(String id, String uid) {
        Image image = getById(id);
        if(image == null) {
            throw new NotFoundException("未找到该镜像");
        }
        // 1、鉴权
        if(!hasAuthImage(uid, image)) {
            throw new ForbiddenException("无权限查看此镜像");
        }

        try {
            List<ImageHistory> history = dockerClient.history(image.getFullName());
            return history;
        } catch (Exception e) {
            log.error("查看镜像源码文件异常，错误位置：{}，错误栈：{}",
                    "SysImageServiceImpl.imageFile", HttpClientUtils.getStackTraceAsString(e));
            throw new InternalServerErrorException("查看历史失败，Docker异常");
        }
    }

    /**
     * 清理缓存
     */
    @Override
    public void cleanCache(String id, String fullName) {
        try {
            if (StringUtils.isNotBlank(id)) {
                jedisClient.hdel(key, ID_PREFIX + id);
            }
            if (StringUtils.isNotBlank(fullName)) {
                jedisClient.hdel(key, FULL_NAME_PREFIX + fullName);
            }
        } catch (Exception e) {
            log.error("清理本地镜像缓存失败，错误位置：{}，错误栈：{}",
                    "SysImageServiceImpl.cleanCache()", HttpClientUtils.getStackTraceAsString(e));
        }
    }

    /**
     * 公开/关闭私有镜像
     * 仅所有者本人操作
     */
    @Override
    public void changOpenImage(String id, String uid, boolean code) {
        Image image = getById(id);
        if(image == null) {
            throw new NotFoundException("未找到该镜像");
        }

        if(ImageTypeEnum.LOCAL_USER_IMAGE.getCode() != image.getType() || !uid.equals(image.getUserId())) {
            throw new ForbiddenException("无权限公开/取消公开此镜像");
        }

        // 修改状态
        if(image.getHasOpen() != code) {
            image.setHasOpen(code);
            imageMapper.updateById(image);
            // 清除缓存
            cleanCache(image.getId(), image.getFullName());
        }
    }

    @Override
    public ArrayList<String> listExportPorts(String imageId, String userId) {
        Image image = getById(imageId);
        if(image == null) {
            throw new NotFoundException("未找到该镜像");
        }

        // 鉴权
        if(!hasAuthImage(userId, image)) {
            throw new ForbiddenException("无权限查看此镜像");
        }

        // 获取端口号
        try {
            ImageInfo info = dockerClient.inspectImage(image.getFullName());
            // 形如：["80/tcp"]
            ImmutableSet<String> exposedPorts = info.containerConfig().exposedPorts();

            Set<String> res = new HashSet<>();

            // 取出端口号信息
            if(exposedPorts != null && exposedPorts.size() > 0) {
                exposedPorts.forEach(s -> {
                    res.add(s.split("/")[0]);
                });
            }

            return new ArrayList<String>(res);
        } catch (DockerRequestException requestException){
            throw new InternalServerErrorException("Docker异常");
        }catch (Exception e) {
            log.error("获取镜像暴露端口错误，出错位置：{}，出错镜像ID：{}，错误栈：{}",
                    "SysImageServiceImpl.listExportPorts()", imageId, HttpClientUtils.getStackTraceAsString(e));
            throw new InternalServerErrorException("Docker异常");
        }
    }

    /**
     * 判断是否有权限查看镜像
     */
    @Override
    public Boolean hasAuthImage(String userId, Image image) {
        // 1、如果镜像是公有镜像 --> true
        if(ImageTypeEnum.LOCAL_PUBLIC_IMAGE.getCode() == image.getType()) {
            return true;
        }
        // 2、如果镜像是用户镜像
        if(ImageTypeEnum.LOCAL_USER_IMAGE.getCode() == image.getType()) {
            // 2.1、如果公开
            if(image.getHasOpen()) {
                return true;
            }
            // 2.2、如果用户角色为USER，且不是自己的 --> false
            String roleName = loginService.getRoleName(userId);
            return !RoleEnum.ROLE_USER.getMessage().equals(roleName) || userId.equals(image.getUserId());
        }
        return false;
    }

    @Override
    public Page<Image> selfImage(String userId, Page<Image> page) {
        List<Image> records = imageMapper.listSelfImage(userId, page);

        return page.setRecords(records);
    }

    @Override
    public Map cleanImage() {
        List<Image> images =  imageMapper.selectList(new QueryWrapper<Image>().eq("name", "<none>"));
        int success = 0, error = 0;
        try {
            for(Image image : images) {
                dockerClient.removeImage(image.getImageId());
                success++;
            }
        } catch (Exception e) {
            log.error("清理镜像出现异常，异常位置：{}，异常栈：{}",
                    "SysImageServiceImpl.cleanImage()", e.getMessage());
            error++;
        }

        Map<String, Integer> map = new HashMap<>();
        map.put("success", success);
        map.put("error", error);
        return map;
    }

    @Override
    public boolean saveImage(String fullName) {
        // 如果数据已存在，不再保存
        if(getByFullName(fullName) != null) {
            return true;
        }

        try {
            List<com.spotify.docker.client.messages.Image> list = dockerClient.listImages(DockerClient.ListImagesParam.byName(fullName));
            com.spotify.docker.client.messages.Image image = CollectionUtils.getListFirst(list);

            Image sysImage = imageToSysImage(image, image.repoTags().get(0));
            // 插入数据
            imageMapper.insert(sysImage);
            return true;
        } catch (Exception e) {
            log.error("保存镜像数据错误，错误位置：{}，错误栈：{}",
                    "SysImageServiceImpl.saveImage()", HttpClientUtils.getStackTraceAsString(e));
            return false;
        }
    }

    private List<ImageDTO> sysImage2DTO(List<Image> list) {
        return list.stream().map(this::sysImage2DTO).collect(Collectors.toList());
    }

    private ImageDTO sysImage2DTO(Image image){
        ImageDTO dto = new ImageDTO();
        BeanUtils.copyProperties(image, dto);

        Auth login = loginService.getById(image.getId());
        if(login != null) {
            dto.setUsername(login.getUsername());
        }
        return dto;
    }

    /**
     * 拆分repoTage
     * 包含：fullName、tag、repo、type、name
     *      当type = LOCAL_USER_IMAGE时，包含userId
     */
    private Map<String, Object> splitRepoTag(String repoTag) {
        Map<String, Object> map = new HashMap<>(16);
        boolean flag = true;
        //设置tag
        int tagIndex = repoTag.lastIndexOf(":");
        String tag = repoTag.substring(tagIndex+1);

        map.put("fullName", repoTag);
        map.put("tag", tag);

        String tagHead = repoTag.substring(0, tagIndex);
        String[] names = tagHead.split("/");

        if(names.length == 1) {
            // 如果包含1个部分，代表来自官方的Image，例如nginx
            map.put("repo", "library");
            map.put("name", names[0]);
            map.put("type", ImageTypeEnum.LOCAL_PUBLIC_IMAGE.getCode());
        } else if(names.length == 2) {
            // 如果包含2个部分，代表来自指定的Image，例如：portainer/portainer
            map.put("repo", names[0]);
            map.put("name", names[1]);
            map.put("type", ImageTypeEnum.LOCAL_PUBLIC_IMAGE.getCode());
        } else if(names.length == 3) {
            // 如果包含3个部分，代表来自用户上传的Image，例如：local/jitwxs/hello-world
            map.put("repo", names[0]);
            map.put("type", ImageTypeEnum.LOCAL_USER_IMAGE.getCode());
            map.put("userId", names[1]);
            map.put("name", names[2]);
        } else {
            // 其他情况异常，形如：local/jitwxs/portainer/portainer:latest
            flag = false;
        }

        // 状态
        map.put("status", flag);

        return map;
    }

    /**
     * 拆分ImageId，去掉头部，如：
     * sha256:e38bc07ac18ee64e6d59cf2eafcdddf9cec2364dfe129fe0af75f1b0194e0c96
     * -> e38bc07ac18ee64e6d59cf2eafcdddf9cec2364dfe129fe0af75f1b0194e0c96
     */
    private String splitImageId(String imageId) {
        String[] splits = imageId.split(":");
        if(splits.length == 1) {
            return imageId;
        }

        return splits[1];
    }

    /**
     * dockerClient.Image --> entity.SysImage
     * 注：hasOpen、createDate、updateDate属性无法计算出，使用默认值
     */
    private Image imageToSysImage(com.spotify.docker.client.messages.Image image, String repoTag) {
        Image sysImage = new Image();
        // 设置ImageId
        sysImage.setImageId(splitImageId(image.id()));

        // 获取repoTag
        Map<String, Object> map = splitRepoTag(repoTag);

        // 判断状态
        if(!(Boolean)map.get("status")) {
            log.error("解析repoTag出现异常，错误目标为：{}", map.get("fullName"));
            return null;
        }

        // 设置完整名
        sysImage.setFullName((String)map.get("fullName"));
        // 设置Tag
        sysImage.setTag((String)map.get("tag"));
        // 设置Repo
        sysImage.setRepo((String)map.get("repo"));
        // 设置name
        sysImage.setName((String)map.get("name"));
        // 设置type
        Integer type = (Integer)map.get("type");
        sysImage.setType(type);
        // 如果type为LOCAL_USER_IMAGE时
        if (ImageTypeEnum.LOCAL_USER_IMAGE.getCode() == type) {
            // 设置userId
            sysImage.setUserId((String)map.get("userId"));
            // 用户镜像默认不分享
            sysImage.setHasOpen(false);
        }

        // 设置CMD
        try {
            ImageInfo info = dockerClient.inspectImage(repoTag);
            sysImage.setCmd(JsonUtils.objectToJson(info.containerConfig().cmd()));
        } catch (Exception e) {
            log.error("获取镜像信息错误，错误位置：{}，错误栈：{}",
                    "SysImageServiceImpl.imageToSysImage()", HttpClientUtils.getStackTraceAsString(e));
        }

        // 设置大小
        sysImage.setSize(image.size());
        // 设置虚拟大小
        sysImage.setVirtualSize(image.virtualSize());
        // 设置Label
        sysImage.setLabels(JsonUtils.mapToJson(image.labels()));
        // 设置父节点
        sysImage.setParentId(image.parentId());
        sysImage.setCreateDate(new Date());

        return sysImage;
    }

    /**
     * 发送系统镜像消息
     */
    private void sendMQ(String userId, String imageId, ResultVO resultVO) {
        Destination destination = new ActiveMQQueue("MQ_QUEUE_SYS_IMAGE");
        Task task = new Task();

        Map<String, Object> data = new HashMap<>(16);
        data.put("type", WebSocketTypeEnum.SYS_IMAGE.getCode());
        data.put("imageId", imageId);
        // 获取暴露端口
        try {
            List<String> ports = listExportPorts(imageId, userId);
            data.put("exportPort", ports);
        } catch (Exception ignored) {};

        resultVO.setData(data);

        Map<String,String> map = new HashMap<>(16);
        map.put("uid",userId);
        map.put("data", JsonUtils.objectToJson(resultVO));
        task.setData(map);

        mqProducer.send(destination, JsonUtils.objectToJson(task));
    }
}