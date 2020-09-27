package com.buaa.paas.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buaa.paas.commons.util.StringUtils;
import com.buaa.paas.exception.BadRequestException;
import com.buaa.paas.exception.ForbiddenException;
import com.buaa.paas.exception.InternalServerErrorException;
import com.buaa.paas.model.dto.ImageDTO;
import com.buaa.paas.model.entity.Image;
import com.buaa.paas.model.enums.ImageTypeEnum;
import com.buaa.paas.model.enums.RoleEnum;
import com.buaa.paas.service.ImageService;
import com.buaa.paas.service.AuthService;
import com.spotify.docker.client.messages.ImageHistory;
import com.spotify.docker.client.messages.ImageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 镜像Controller
*/
@RestController
@RequestMapping("/image")
public class ImageController {
    @Autowired
    private AuthService loginService;
    @Autowired
    private ImageService imageService;

    /**
     * 查找本地(服务器)镜像
     * 包含本地公共和本地用户镜像
     */
    @GetMapping("/list/local")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public Page<ImageDTO> searchLocalImage(@RequestAttribute String uid,  String name, Integer type,
                                     @RequestParam(defaultValue = "1") Integer current,
                                     @RequestParam(defaultValue = "10") Integer size) {
        // 判断参数
        if(type == null) {
            throw new BadRequestException("未指定查看镜像类型");
        }

        Page<ImageDTO> page = new Page<ImageDTO>(current, size, false);

        String roleName = loginService.getRoleName(uid);

        if (type == ImageTypeEnum.LOCAL_PUBLIC_IMAGE.getCode()) {
            // 本地公共镜像
            return imageService.listLocalPublicImage(name, page);
        } else if (type == ImageTypeEnum.LOCAL_USER_IMAGE.getCode()) {
            // 系统管理员查看所有本地用户镜像，普通用户只能查看公开的本地用户镜像和自己镜像
            if(RoleEnum.ROLE_USER.getMessage().equals(roleName)) {
                return imageService.listLocalUserImage(name, true, uid, page);
            } else {
                return imageService.listLocalUserImage(name, false, uid, page);
            }

        } else {
            throw new BadRequestException("指定的镜像类型不合法");
        }
    }

    /**
     * 查看个人上传的所有镜像
     * @return
     */
    @GetMapping("/self")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public Page<Image> selfImage(@RequestAttribute String uid, Page<Image> page) {
        return imageService.selfImage(uid, page);
    }

    /**
     * 查询镜像的详细信息
     * 注：只能查询本地镜像
     */
    @GetMapping("/inspect/{id}")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public ImageInfo imageInspect(@RequestAttribute("uid") String uid, @PathVariable String id) {
        return imageService.inspectImage(id, uid);
    }

    /**
     * 本地镜像同步
     * 同步本地镜像和数据库信息
     */
    @GetMapping("/sync")
    @PreAuthorize("hasRole('ROLE_SYSTEM')")
    public void syncLocalImage() {
        imageService.sync();
    }

    /**
     * 删除镜像
     * 普通用户只能删除自己上传的镜像
     * @param id 镜像ID
     */
    @DeleteMapping("/delete/{id}")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public void deleteImage(@RequestAttribute String uid, @PathVariable String id, HttpServletRequest request) {
        imageService.removeImage(id, uid, request);
    }

    /**
     * 查看镜像History
     * @return
     */
    @GetMapping("/history/{id}")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public List<ImageHistory> lookImage(@RequestAttribute String uid, @PathVariable String id) {
        return imageService.getHistory(id, uid);
    }

    /**
     * 将私有镜像公开
     */
    @GetMapping("/share/{id}")
    @PreAuthorize("hasRole('ROLE_USER')")
    public void shareImage(@RequestAttribute String uid, @PathVariable String id) {
        imageService.changOpenImage(id, uid, true);
    }

    /**
     * 将私有镜像取消公开
     */
    @GetMapping("/disShare/{id}")
    @PreAuthorize("hasRole('ROLE_USER')")
    public void disShareImage(@RequestAttribute String uid, @PathVariable String id) {
        imageService.changOpenImage(id, uid, false);
    }

    /**
     * 导入镜像【WebSocket】
     * @param file 镜像文件，只能为tar.gz文件
     * @param imageName 镜像名，不能包含大写字符
     * @param tag 镜像标签，默认为latest
     */
    @PostMapping("/import")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public String importImage(String imageName, @RequestParam(defaultValue = "latest") String tag, MultipartFile file,
                                @RequestAttribute String uid, HttpServletRequest request) {
        // 校验参数
        if(StringUtils.isBlank(imageName) || file == null) {
            throw new BadRequestException("镜像名不合法");
        }
        // 判断镜像名是否有大写字符
        for(int i=0; i<imageName.length(); i++) {
            if(Character.isUpperCase(imageName.charAt(i))){
                throw new BadRequestException("镜像名有大写字符");
            }
        }
        // 判断文件后缀
        if(!file.getOriginalFilename().endsWith(".tar.gz")) {
            throw new BadRequestException("文件后缀不为tar.gz");
        }

        // 拼接完整名：repo/userId/imageName:tag
        String fullName = "local/" + uid + "/" + imageName + ":" + tag;
        // 判断镜像是否存在
        if(imageService.getByFullName(fullName) != null) {
            throw new ForbiddenException("镜像已存在");
        }

        try {
            InputStream stream = file.getInputStream();
            imageService.importImageTask(stream, fullName, uid, request);
            return "开始导入镜像";
        } catch (IOException e) {
            throw new InternalServerErrorException("镜像导入出错");
        }
    }

    /**
     * 获取镜像所有暴露接口
     * @return
     */
    @GetMapping("/{id}/exportPort")
    @PreAuthorize("hasRole('ROLE_USER') or hasRole('ROLE_SYSTEM')")
    public ArrayList<String> listExportPort(@RequestAttribute String uid, @PathVariable String id) {
        return imageService.listExportPorts(id, uid);
    }

    @GetMapping("/clean")
    @PreAuthorize("hasRole('ROLE_SYSTEM')")
    public Map cleanImage() {
        return imageService.cleanImage();
    }

}