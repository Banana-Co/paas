package com.buaa.paas.commons.convert;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buaa.paas.commons.util.StringUtils;
import com.buaa.paas.model.dto.ContainerDTO;
import com.buaa.paas.model.entity.Container;
import com.buaa.paas.model.enums.ContainerStatusEnum;
import com.buaa.paas.service.AuthService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * UserContainer --> UserContainerDTO
 */
@Component
public class UserContainerDTOConvert {
//    @Autowired
//    private UserProjectService projectService;
    @Autowired
    private AuthService authService;
    @Value("${docker.server.address}")
    private String serverIp;

    public ContainerDTO convert(Container container) {
        if(container == null) {
            return null;
        }
        ContainerDTO dto = new ContainerDTO();
        BeanUtils.copyProperties(container, dto);

//        String projectId = container.getProjectId();
//        if(StringUtils.isNotBlank(projectId)) {
//            String projectName = projectService.getProjectName(projectId);
//            dto.setProjectName(projectName);
//        }

        Integer status = container.getStatus();
        if(status != null) {
            dto.setStatusName(ContainerStatusEnum.getMessage(status));
        }

//        String userId = projectService.getUserId(container.getProjectId());
        String userId = container.getUserId();
//        if(StringUtils.isNotBlank(projectId)) {
            String username= authService.getById(userId).getUsername();
            dto.setUsername(username);
//        }

//        if(StringUtils.isNotBlank(projectId)) {
//            String projectName = projectService.getProjectName(projectId);
//            dto.setProjectName(projectName);
//        }

        dto.setIp(serverIp);

        return dto;
    }

    public List<ContainerDTO> convert(List<Container> containers) {
        return containers.stream().map(this::convert).collect(Collectors.toList());
    }

    public Page<ContainerDTO> convert(Page<Container> page) {
        List<Container> containers = page.getRecords();
        List<ContainerDTO> containerDTOS = convert(containers);

        Page<ContainerDTO> page1 = new Page<>();
        BeanUtils.copyProperties(page, page1);
        page1.setRecords(containerDTOS);

        return page1;
    }
}